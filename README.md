# Notification System

>  Backend System Design Lab — Week 2

Push, SMS, Email 알림 시스템을 구현한 뒤 부하와 장애를 직접 만들어보면서 구조를 단계적으로 개선한 프로젝트입니다.

## 프로젝트 소개

처음에는 Notification API가 DB Transaction 안에서 Mock Provider를 동기 호출했습니다.   

50 VU에서 HikariCP 10개가 모두 사용되고 약 40개의 요청이 Connection을 기다리면서   
RPS는 23.55, p95는 3.80초까지 증가했습니다.

이후 Transaction Boundary 분리, RabbitMQ 비동기 처리,   
Consumer Concurrecny / Prefetch 튜닝, Redis Cache,   
Retry / DLQ, Transactional Outbox를 순서대로 적용하고 같은 방식으로 측정했습니다.

```text
Synchronous Provider Call
→ Transaction Boundary 분리
→ RabbitMQ Async
→ Consumer Concurrency / Prefetch 튜닝
→ Redis Preference Cache / Dedup / Rate Limit
→ Retry / DLQ
→ Transactional Outbox
→ Virtual Thread 비교
```

## 핵심 결과

| 실험 | 결과 |
| --- | --- |
| Transaction Boundary | 50 VU RPS 23.55 → 117.74 |
| RabbitMQ Async | 50 VU RPS 117.74 → 511.23 |
| 응답 지연 | Baseline p95 3.80s → Async p95 266.21ms |
| Consumer Concurrency | Queue당 Ack Rate 약 9 → 44 → 89 msg/s |
| 최종 Consumer 설정 | Concurrency 5, Prefetch 50 |
| Preference Cache | RPS 180.51 → 513.69, p95 93.51ms → 18.27ms |
| Dedup Cache | RPS 1,044.11 → 3,957.98, p95 93.51ms → 18.27ms |
| Virtual Thread | 200 VU p99 1.69s → 1.06s |
| Retry / DLQ | Provider 3회 실패 후 FAILED 및 DLQ |
| Transactional Outbox | RabbitMQ 장애 시 PENDING 보존 후 복구 시 재발행 |

## 기술 스택

| 구분                 | 기술 |
|--------------------| --- |
| Application        | Java 25, Spring Boot 4.1, Spring Data JPA |
| Database           | MySQL 8.4 |
| Cache / Rate Limit | Redis 7.4 |
| Message Broker     | RabbitMQ 4 |
| Monitoring         | Spring Boot Actuator, Micrometer, Prometheus, Grafana |
| Performance Test | k6 |
| Infrastructure | Docker, Docker Compose |

## 아키텍처

현재 구현에서는 Notification API와 RabbitMQ Consumer가 동일한 Spring Boot Application에서 실행됩니다.
```text
                  Internal Service
                         │
                 Notification API
                         │
          ┌──────────────┴──────────────┐
          │                             │
        Redis                         MySQL
 Preference / Dedup /          Notification / Delivery
    Rate Limit                    / OutboxEvent
                                        │
                                Outbox Publisher
                                        │
                                    RabbitMQ
                      ┌─────────────────┼─────────────────┐
                  Push Queue         SMS Queue        Email Queue
                      │                 │                 │
               Push Consumer      SMS Consumer      Email Consumer
                      │                 │                 │
                    Mock Provider / External Provider

```

운영 환경에서는 API와 Consumer Worker를 별도 프로세스로 분리하고   
채널별 Worker를 독립적으로 수평 확장하는 구조를 고려합니다.

## 주요 설계

### RabbitMQ 비동기 처리

초기에는 Provider 호출이 HTTP 요청 경로에 포함되어 있었습니다.

```text
HTTP Request
→ DB 저장
→ Provider 호출
→ HTTP Response
```

RabbitMQ 적용 후:

```text
HTTP Request
→ DB 저장
→ Outbox
→ 202 Accepted

RabbitMQ
→ Consumer
→ Provider
```

Provider 지연과 장애를 API 요청 경로에서 분리했습니다.

### Consumer Concurrency / Prefetch

Concurrency를 1, 5, 10으로 비교했을 때 Queue당 Ack Rate는   
약 9, 44, 89 msg/s로 증가했습니다.

Concurrency 10에서는 CPU와 HikariCP 경합이 커져   
현재 단일 인스턴스에서는 **Concurrency 5**를 선택했습니다.

Prefetch는 10, 50, 250을 비교했습니다.

Prefetch 50 이후 실제 Ack Rate는 거의 증가하지 않았지만   
Unacked 메시지는 크게 늘어 최종값을 **50**으로 선택했습니다.

### Redis

Redis는 Source of Truth가 아니라 보조 계층으로 사용합니다.

```text
Preference Cache
→ 반복 DB 조회 감소

Dedup Cache
→ 동일 Event ID 중복 요청 빠른 응답

Rate Limit
→ Provider 보호
```

Redis 장애 시:
```text
Preference Cache → MySQL Fallback
Dedup Cache      → MySQL + event_id UNIQUE
Rate Limit       → Fail-open
```

### Retry / DLQ
```text
Provider 실패
→ Retry Queue
→ TTL
→ 원 Queue 재진입
→ 최대 3회 실패
→ DLQ
```

일시적 장애는 재시도하고 반복 실패는 DLQ로 격리합니다.

### Transactional Outbox

DB 저장과 RabbitMQ Publish 사이의 메시지 유실을 방지합니다.

```text
DB Transaction
├─ Notification
├─ Delivery
└─ OutboxEvent PENDING
        │
        ↓
Outbox Publisher
        │
        ↓
RabbitMQ Publish
        │
        ↓
PUBLISHED
```

RabbitMQ 장애 시 `PENDING` 상태를 유지하고 복구 후 다시 발행합니다.

### At-least-once + Idempotency

외부 Provider까지 포함한 Exactly-once 대시    
At-least-once 전달을 기본으로 두고 Event ID 기반 멱등성으로 중복을 줄였습니다.


## 실행 방법

### 사전 요구사항
* Java 25
* Docker / Docker Compose
* k6

### 환경 실행 
```bash
docker compose up -d --build
```

상태 확인:
```bash
docker compose ps
curl http://localhost:8080/actuator/health
```

### API
알림 요청: 
```bash
curl -X POST http://localhost:8080/api/v1/notifications \
  -H 'Content-Type: application/json' \
  -d '{
    "eventId": "notification-001",
    "userId": 1,
    "channels": ["PUSH", "SMS", "EMAIL"]
  }'
```

Preference 변경:
```bash
curl -X PATCH \
  http://localhost:8080/api/v1/users/1/notification-preferences/SMS \
  -H 'Content-Type: application/json' \
  -d '{"enabled": false}'
```

### 테스트
```bash
./gradlew clean test
```

상세 실험 스크립트와 결과는 `k6/`, `scripts/`, `docs/04-experiment.md`에서 확인할 수 있습니다.

## 문서

| 문서 | 내용 |
|---|---|
| [Requirements](docs/01-requirements.md) | 범위와 성공 기준 |
| [Capacity](docs/02-capacity-estimation.md) | 트래픽, 저장량, 예상 병목 |
| [Architecture](docs/03-architecture.md) | 최종 구조와 설계 판단 |
| [Experiment](docs/04-experiment.md) | 부하, 병목, 장애 실험 결과 |
| [Retrospective](docs/05-retrospective.md) | 문제 해결 과정과 면접용 정리 |

## 후속 과제

* API와 Consumer Worker 분리
* 채널별 Worker 독립 수평 확장
* RabbitMQ Cluster 구성
* MySQL Replica / Failover
* Redis Sentinel 또는 Cluster
* End-to-End Delivery Latency 측정
* 실제 APNs / FCM / SMS / Email Provider 연동