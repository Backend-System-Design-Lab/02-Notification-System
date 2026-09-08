# 05. Retrospective

## 1. 해결한 문제

처음에는 Push, SMS, Email을 전송하는 기능 자체가 핵심이라고 생각했다.

하지만 도기식 Baseline에서 50 VU 기준 HikariCP Connection 10개가 모두 사용되고   
약 40개의 요청이 Connection을 기다리면서 RPS가 23.55에 머물렀다.

원인은 Provider 호출을 DB Transaction 내부에서 수행해    
외부 I/O 동안 Connection을 계속 점유한 구조였다.

이후 프로젝트는 기능 추가보다 **측정된 병목을 하나씩 제거하는 방식**으로 진행했다.

```text
DB Connection 병목
→ Transaction Boundary 분리
→ Provider 동기 호출 병목
→ RabbitMQ 비동기화
→ Consumer Backlog
→ Concurrency / Prefetch 튜닝
→ 반복 DB 조회
→ Redis Cache
→ 장애와 메시지 유실
→ Retry / DLQ / Transactional Outbox
```

## 2. 최종 결과

| 항목                   | 결과                                           |
|----------------------|----------------------------------------------|
| 동기 Baseline          | 50 VU, 23.55 RPS, p95 3.80s                  |
| Transaction Boundary | 50 VU, 117.74 RPS, p95 456.86ms              |
| RabbitMQ 비동기         | 50 VU, 511.23 RPS, p95 266.21ms              |
| Consumer Ack Rate    | Concurrency 1/5/10 → 약 9/44/89 msg/s         |
| 최종 Consumer 설정       | Concurrency 5, Prefetch 50                   |
| Preference Cache     | RPS 180.51 → 513.69, p95 455.88ms → 129.45ms |
| Dedup Cache | RPS 1,044.11 → 3,957.98, p95 93.51ms → 18.27ms |
| Virtual Thread 200 VU | RPS 542.68 → 579.90, p99 1.69s → 1.06s |
| Retry / DLQ | Rrovider 3회 실패 후 FAILED 및 DLQ |
| Transactional Outbox | RabbitMQ 장애 시 PENDING 보존 후 재발행 |
| Redis 장애 | Preference/Dedup DB Fallback, Rate Limit Fail-open |

용량 산정에서 피크 Notification 요청을 약 925 RPS로 가정했다.

단일 로컬 인스턴스에서는 일반 알림 요청으로 목표치를 달성하지 못했으며,   
운영 환경에서는 API와 Worker를 분리하고 수평 확장한 뒤 다시 검증할 필요가 있다.

## 3. 주요 설계 판단

### 가장 잘한 결정: Baseline을 먼저 측정

처음부터 RabbitMQ와 Redis를 적용하지 않고 동기 구조를 먼저 측정했다.

```text
50 VU
RPS 23.55
p95 3.80s
HikariCP Active 10
Pending 약 40
```

이 결과를 통해 Provider I/O 중 DB Connection 점유가 첫 병목이라는 근거를 얻었다.

각 기술을 "많이 쓰이는 기술"이 아니라   
**직전 단계에서 확인한 문제를 해결하기 위해 선택했다는 점**이 가장 큰 의미였다.

### 가장 중요한 결정: Transactional Outbox

RabbitMQ 비동기화만 적용하면 다음 문제가 남는다.

```text
DB Commit 성공
RabbitMQ Publish 실패
```

이를 막기 위해 Notification, Delivery, Outbox를 같은 DB Transaction에 저장하고   
Outbox Publisher가 별도로 RabbitMQ에 전달하도록 했다.

RabbitMQ 중단 테스트에서 Outbox가 `PENDING`으로 유지되고   
복구 후 다시 발행되는 것을 확인했다.

### 다시 검토할 결정: API와 Consumer를 같은 프로세스에 둔 것

Consumer Concurrency를 높일수록 Ack Rate는 증가했지만   
API와 Consumer가 CPU와 HikariCP Pool을 함께 사용해 자원 경합이 커졌다.

운영 환경에서는 다음처럼 분리하는 편이 적합하다.

```text
Notification API
      ↓
RabbitMQ
      ↓
Push / SMS / Email Worker
```

## 4. 발생한 문제와 해결 과정

### 문제 1. Provider I/O가 DB Connection을 점유

#### 원인

Provider 100ms 호출을 최대 4회 Transaction 안에서 수행했다.

#### 해결

```text
DB 저장 Transaction
→ Provider 호출
→ 상태 갱신 Transaction
```

#### 결과

```text
50 VU
RPS 23.55 → 117.74
p95 3.80s → 456.86ms
```

### 문제 2. RabbitMQ 적용 후 Queue 적체

#### 원인

API가 Consumer보다 빠르게 메시지를 생성했다.

#### 해결

Concurrency와 Prefetch를 각각 비교했다.

```text
Concurrency 1/5/10
Ack Rate 약 9/44/89 msg/s
```

Concurrency 10은 CPU와 DB Pool 경합이 커 최종값을 5로 정했다.

Prefetch는 50 이후 Ack Rate 향상이 거의 없어 50을 선택했다.

### 문제 3. 동일 Preference 반복 DB 조회

#### 해결

Redis Cache-Aside를 적용하고 Preference 변경 시 DB 갱신 후 Cache를 Evict했다.

#### 결과

```text
RPS 180.51 → 513.69
p95 455.88ms → 129.45ms
```

Redis 장애 시에는 MySQL로 Fallback한다.


### 문제 4. 동일 Event ID 동시 요청

Redis는 빠른 중복 응답 경로로만 사용하고   
MySQL `event_id UNIQUE`를 최종 정합성 수단으로 유지했다.

UNIQUE 충돌 시 실패한 Transaction 밖에서 기존 Notification을 다시 조회한다.

Dedup 전용 부하에서는:

```text
RPS 1,044.11 → 3,957.98
p95 93.51ms → 18.27ms
```

로 개선됐다.

### 문제 5. RabbitMQ 장애 시 메시지 유실 가능성

Transactional Outbox를 적용해 DB와 메시지 발행 사이의 유실을 막았다.

```text
DB Commit
→ Outbox PENDING
→ RabbitMQ 복구
→ 재발행
→ PUBLISHED
```

Publisher Confirm과 Return으로 Broker 수신과 라우팅 실패도 확인했다.

### 문제 6. Virtual Thread만으로 병목이 사라지지 않음

200 VU에서 Virtual Thread는 일부 개선을 보였다.

```text
RPS 542.68 → 579.90
p99 1.69s → 1.06s
```

하지만 약 190개의 HikariCP Pending이 발생했다.

Virtual Thread는 Blocking 대기 비용을 줄이지만   
DB Connection Pool 용량 자체를 늘리지는 않기 때문에   
높은 부하에서는 DB Pool이 더 큰 제한 요소였다.

## 5. 트레이드오프

| 선택                        | 얻은 것                | 추가된 부담             |
|---------------------------|---------------------|--------------------|
| Transactional Boundary 분리 | DB Connection 점유 감소 | Transaction 경계 복잡도 |
| RabbitMQ | 빠른 API 응답, Provider 장애 격리 | Queue/Consumer 운영 |
| Retry + DLQ | 일시적 장애 복구, 최종 실패 격리 | 재시도 정책 | 
| Transactional Outbox | 메시지 유실 방지 | Outbox 저장/정리 |
| Preference Cache | 반복 DB 조회 감소 | Cache 무효화 |
| Dedup Cache | 중복 요청 빠른 응답 | Cache와 DB 상태 차이 가능 |
| Rate Limit | Provider 보호 | Redis 장애 정책 |
| At-least-once | 유실 가능성 감소 | 중복 가능성 관리 |
| Virtual Thread | Blocking 대기 비용 감소 | 외부 자원 한계는 유지 |

## 6. 운영 환경에서 추가할 사항

- [ ] API와 Worker 프로세스 분리
- [ ] API / Worker 수평 확장 
- [ ] Load Balancer
- [ ] MySQL Replica / Failover
- [ ] RabbitMQ Cluster 
- [ ] Redis Sentinel 또는 Cluster
- [ ] Outbox / Delivery Retention
- [ ] 중앙 로그 및 Alerting
- [ ] Secret Manager
- [ ] 백업 및 장애 대응 Runbook
- [ ] Notification 상태 조회 API
- [ ] 실제 외부 Provider 연동

## 7. 기술적으로 배운 점

- 시스템 설계: 한 병목을 제거하면 다음 병목이 다른 계층으로 이동한다.
- Spring Transaction: 외부 I/O를 Transaction 안에서 수행하면 DB Connection을 오래 점유할 수 있다.
- 메시징: API RPS와 실제 Delivery 처리량은 다르며 Queue Backlog와 Ack Rate를 함께 봐야 한다.
- RabbitMQ: Concurrency는 병렬 처리 수, Prefetch는 메시지 선점량을 조절한다.
- 정합성: DB Commit과 Broker Publish 사이의 유실에는 Transactional Outbox가 유효했다.
- Redis: Cache 성능뿐 아니라 Fallback, 정합성, 장애 정책을 함께 설계해야 한다.
- 멱등성: Redis보다 DB UNIQUE를 최종 기준으로 두는 것이 안전하다.
- Virtual Thread: Thread 비용을 줄여도 DB Connection 같은 외부 자원 한계는 남는다.
- 성능 테스트: RPS뿐 아니라 p95, HikariCP Pending, Ready/Unacked를 같이 봐야 병목을 설명할 수 있다.

## 8. 면접 기반 정리

### 30초 요약

동기식 알림 시스템에서는 Provider 호출을 DB Transaction 내부에서 수행하면서   
50 VU 기준 HikariCP가 포화되고 RPS가 23.55에 머무는 문제를 확인했습니다.

Transaction Boundary를 분리한 뒤 RabbitMQ 비동기 처리를 적용해   
API 접수 처리량을 511.23 RPS까지 높였고,   
Consumer Concurrency와 Prefetch를 각각 5와 50으로 조정했습니다.

또한 Transactional Outbox, Retry/DLQ,    
Redis 기반 Preference Cache, Dedup, Rate Limit을 적용해   
메시지 유실과 중복 요청, Provider 장애에 대응했습니다.

Virtual Thread도 비교했지만 높은 부하에서는   
DB Connection Pool이 더 중요한 병목이라는 점을 확인했습니다.

### 핵심 질문

1. 왜 RabbitMQ를 사용했는가?    
Provider 호출을 HTTP 요청 경로에서 분리해 API와 외부 Provider의 처리 속도와 장애를 분리하기 위해 사용했다.   
RabbitMQ 선택 이유: 알림 메시지가 이벤트 로그보다는 처리해야할 작업(Task)의 성격에 가까움, Queue 기반 작업 처리와 Retry/DLQ가 핵심

2. RabbitMQ만으로 왜 부족한가?    
    DB Commit과 RabbitMQ Publsih 사이에서 장애가 발생하면 메시지가 유실될 수 있어 Transactional Outbox를 추가했다. (Dual Write)
3. 왜 At-least-once인가?     
   DB Broker, 외부 Provider까지 포함한 Exactly-once는 보장하기 어려워 유실을 줄이고 멱등성으로 중복을 관리했다.
4. Concurrency와 Prefetch의 차이는?   
   Concurrency는 실제 병렬 Consumer 수를 늘리고 Prefetch는 각 Consumer가 ACK 전 선점하는 메시지 수를 조절한다.
5. Redis가 죽으면?    
    Preference와 Dedup은 DB로 Fallback하고 Rate Limit은 Fail-open 한다.
6. Virtual Thread가 왜 큰 개선을 만들지 못했는가?
    Thread 대기 비용은 줄였지만 HikariCP Connection 수는 그대로라 DB Pool이 먼저 포화됐기 떄문이다.
7. 현재 가장 큰 구조적 한계는?    
   API와 Consumer가 같은 프로세스와 DB Pool을 공유한다는 점이며 운영 환경에서는 Worker를 분리하는 것이 적합하다.


### 답변에 사용할 수치

- Baseline 50 VU: 23.55 RPS / p95 3.80s 
- Transaction Boundary: 117.74 RPS / p95 456.86ms 
- RabbitMQ Async: 511.23 RPS / p95 266.21ms 
- Consumer Ack Rate: 약 9 / 44 / 89 msg/s 
- Preference Cache: RPS 180.51 → 513.69 
- Dedup: RPS 1,044.11 → 3,957.98 
- Virtual Thread 200 VU: p99 1.69s → 1.06s 
- 최종 Consumer 설정: Concurrency 5 / Prefetch 50 
- Provider Retry: 최대 3회 
- Outbox Publish 실패: 최대 5회

## 9. 다음 프로젝트에 반영할 점

- 유지: 요구사항 → 용량 산정 → Baseline → 병목 → 개선 → 재측정
- 유지: 정상 성능뿐 아니라 장애 주입과 복구 경로도 검증
- 개선: 주요 실험은 3회 이상 반복해 중앙값 또는 평균 사용
- 개선: Warm-up과 Queue 초기 상태를 명확히 통제
- 개선: API RPS와 End-to-End Delivery 처리량을 별도로 측정
- 다음 실험: API / Worker 분리, RabbitMQ / MySQL Failover, 독립 Failure Domain

* Consumer: 메시지를 읽는 실행 단위, Worker: Consumer를 포함해 실제 비지니스 작업을 수행하는 애플리케이션 또는 프로세스