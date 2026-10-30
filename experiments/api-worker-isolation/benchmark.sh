#!/bin/bash
set -euo pipefail

MODE="${1:-}"

if [[ "$MODE" != "monolith" && "$MODE" != "separated" && "$MODE" != "api-only" && "$MODE" != "worker-idle" ]]; then
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

if [[ "$MODE" != "monolith" ]]; then
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

if [[ "$MODE" == "api-only" ]]; then
  echo "[DIAGNOSTIC] Stop Worker"
  dc stop worker
elif [[ "$MODE" == "worker-idle" ]]; then
  echo "[DIAGNOSTIC] Recreate Worker without consumers"

  docker compose \
    -f docker-compose.yml \
    -f docker-compose.worker-idle.yml \
    up -d --no-deps --force-recreate worker

  wait_ready worker
  sleep 30

  enabled=$(dc exec -T worker printenv \
    NOTIFICATION_CONSUMER_ENABLED | tr -d '\r')

  if [[ "$enabled" != "false" ]]; then
    echo "[ERROR] Worker consumer is still enabled"
    exit 1
  fi
fi


capture_diag() {
  local phase="$1"
  local prefix="$DIR/${MODE}-${RUN_ID}-diag-${phase}"

  # MySQL 전체 행 잠금 지표
  dc exec -T mysql mysql -N -B     -unotification -pnotification     -e "SHOW GLOBAL STATUS WHERE Variable_name IN (
      'Innodb_row_lock_waits',
      'Innodb_row_lock_time',
      'Innodb_row_lock_current_waits',
      'Innodb_log_waits'
    );" > "${prefix}-mysql.txt" 2>/dev/null || true

  # JVM 및 HikariCP 지표
  for service in app worker; do
    if [[ "$service" == "worker" && "$MODE" != "separated" ]]; then
      continue
    fi

    for metric in       jvm.gc.pause       process.cpu.time       hikaricp.connections.acquire       hikaricp.connections.usage
    do
      dc exec -T "$service"         curl -fsS --max-time 3         "http://localhost:8080/actuator/metrics/$metric"         > "${prefix}-${service}-${metric}.json" 2>/dev/null ||         echo '{}' > "${prefix}-${service}-${metric}.json"
    done
  done
}

echo "[7] Start HikariCP monitoring"

(
  while true; do
    for metric_service in app worker; do
      if [[ "$MODE" != "separated" && "$MODE" != "worker-idle" &&
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

# k6 측정 구간의 컨테이너 자원 사용량 기록
RESOURCE_CONTAINERS=(
  02-notification-system-app-1
  02-notification-system-mysql-1
  02-notification-system-redis-1
  notification-rabbitmq
)

if [[ "$MODE" == "separated" || "$MODE" == "worker-idle" ]]; then
  RESOURCE_CONTAINERS+=(02-notification-system-worker-1)
fi

(
  while true; do
    echo "===== $(date '+%H:%M:%S') ====="

    docker stats --no-stream \
      --format '{{.Name}} CPU={{.CPUPerc}} MEM={{.MemUsage}}' \
      "${RESOURCE_CONTAINERS[@]}" 2>/dev/null || true

    sleep 1
  done
) > "$DIR/${MODE}-${RUN_ID}-resources.txt" &

STATS_PID=$!

trap 'kill "$MONITOR_PID" "$STATS_PID" 2>/dev/null || true' EXIT

capture_diag before

echo "[8] Run k6 benchmark"

if [[ "${LOAD_MODE:-vus}" == "arrival" ]]; then
  dc run --rm --no-deps k6 run \
    -e RATE="${RATE:-200}" \
    /scripts/notification-fixed-rate.js \
    2>&1 | tee "$DIR/${MODE}-${RUN_ID}-k6-arrival.txt"
else
  dc run --rm --no-deps k6 run \
    --vus 50 \
    --duration 15s \
    /scripts/notification-async.js \
    2>&1 | tee "$DIR/${MODE}-${RUN_ID}-k6.txt"
fi

capture_diag after

kill "$MONITOR_PID" "$STATS_PID" 2>/dev/null || true
wait "$MONITOR_PID" "$STATS_PID" 2>/dev/null || true

trap - EXIT

echo "===== FINISHED: $MODE ====="
echo "Results: $DIR"

