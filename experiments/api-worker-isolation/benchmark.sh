#!/bin/bash
set -euo pipefail

MODE="${1:-}"

if [[ "$MODE" != "monolith" && "$MODE" != "separated" ]]; then
  echo "Usage: $0 monolith|separated"
  exit 1
fi

DIR="experiments/api-worker-isolation"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$DIR"

COMPOSE=(-f docker-compose.yml)

if [[ "$MODE" == "monolith" ]]; then
  COMPOSE+=(-f docker-compose.monolith.yml)
fi

dc() {
  docker compose "${COMPOSE[@]}" "$@"
}

reset_data() {
  echo "[RESET] MySQL"

  dc exec -T mysql \
    mysql -unotification -pnotification notification <<'SQL'
START TRANSACTION;
DELETE FROM outbox_events;
DELETE FROM notification_deliveries;
DELETE FROM notifications;
COMMIT;
SQL

  echo "[RESET] RabbitMQ"

  for queue in \
    notification.push.queue \
    notification.sms.queue \
    notification.email.queue \
    notification.push.retry.queue \
    notification.sms.retry.queue \
    notification.email.retry.queue \
    notification.push.throttle.queue \
    notification.sms.throttle.queue \
    notification.email.throttle.queue \
    notification.push.dlq \
    notification.sms.dlq \
    notification.email.dlq
  do
    docker exec notification-rabbitmq \
      rabbitmqctl purge_queue "$queue" >/dev/null
  done

  echo "[RESET] Redis"
  dc exec -T redis redis-cli FLUSHDB >/dev/null
}

wait_ready() {
  local service="$1"

  echo "[READY] Waiting for $service"

  for i in $(seq 1 90); do
    if dc exec -T "$service" \
      curl -fsS --max-time 2 \
      http://localhost:8080/actuator/health/readiness \
      >/dev/null 2>&1; then
      echo "[READY] $service"
      return 0
    fi

    sleep 2
  done

  echo "[ERROR] $service not ready"
  exit 1
}

wait_drained() {
  echo "[DRAIN] Waiting for warmup deliveries"

  for i in $(seq 1 90); do
    pending=$(dc exec -T mysql \
      mysql -N -s -unotification -pnotification notification \
      -e "
      SELECT
        (SELECT COUNT(*) FROM outbox_events
         WHERE status = 'PENDING')
        +
        (SELECT COUNT(*) FROM notification_deliveries
         WHERE status = 'PENDING');
      " 2>/dev/null | tail -n 1)

    if ! rabbit_rows=$(docker exec notification-rabbitmq \
      rabbitmqctl -q list_queues messages 2>/dev/null); then
      echo "[ERROR] RabbitMQ queue check failed"
      exit 1
    fi

    queued=$(printf '%s\n' "$rabbit_rows" |
      awk '$1 ~ /^[0-9]+$/ {sum += $1}
           END {print sum+0}')

    echo "[DRAIN] DB pending=$pending, Queue messages=$queued"

    if [[ "$pending" == "0" && "$queued" == "0" ]]; then
      sleep 3
      return 0
    fi

    sleep 2
  done

  echo "[ERROR] Warmup backlog did not drain"
  exit 1
}

echo "===== BENCHMARK: $MODE ====="

echo "[1] Stop applications"
dc stop app worker

echo "[2] Backup database"
dc exec -T mysql \
  mysqldump -unotification -pnotification \
  --no-tablespaces --single-transaction notification \
  > "$DIR/${MODE}-${RUN_ID}-backup.sql"

echo "[3] Reset initial data"
reset_data

echo "[4] Start application"

if [[ "$MODE" == "monolith" ]]; then
  dc up -d --no-deps --force-recreate app
else
  dc up -d --no-deps --force-recreate app worker
fi

wait_ready app

if [[ "$MODE" == "separated" ]]; then
  wait_ready worker
fi

# Give RabbitMQ listeners time to register
sleep 10

echo "[5] Warm up API"

dc run --rm --no-deps k6 run \
  --vus 1 \
  --duration 5s \
  /scripts/notification-async.js \
  > "$DIR/${MODE}-${RUN_ID}-warmup.txt" 2>&1

wait_drained

echo "[6] Reset warmup data"
reset_data

echo "[7] Start HikariCP monitoring"

(
  while true; do
    for metric_service in app worker; do
      if [[ "$MODE" == "monolith" &&
            "$metric_service" == "worker" ]]; then
        continue
      fi

      value=$(dc exec -T "$metric_service" \
        curl -fsS --max-time 2 \
        http://localhost:8080/actuator/metrics/hikaricp.connections.pending \
        2>/dev/null |
        sed -n 's/.*"value":\([0-9.]*\).*/\1/p') || true

      printf '%s %s pending=%s\n' \
        "$(date '+%H:%M:%S')" \
        "$metric_service" \
        "${value:-NA}"
    done

    sleep 1
  done
) > "$DIR/${MODE}-${RUN_ID}-hikari.txt" &

MONITOR_PID=$!

trap 'kill "$MONITOR_PID" 2>/dev/null || true' EXIT

echo "[8] Run k6 benchmark"

dc run --rm --no-deps k6 run \
  --vus 50 \
  --duration 15s \
  /scripts/notification-async.js \
  2>&1 | tee "$DIR/${MODE}-${RUN_ID}-k6.txt"

kill "$MONITOR_PID" 2>/dev/null || true
wait "$MONITOR_PID" 2>/dev/null || true

trap - EXIT

echo "===== FINISHED: $MODE ====="
echo "Results: $DIR"
