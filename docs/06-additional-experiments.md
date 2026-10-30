# API / Worker 실행 환경 분리 및 검증

## 1. 배경과 목표

기존 알림 시스템은 하나의 Spring Boot JVM에서 HTTP API, Transactional Outbox Publisher, RabbitMQ Consumer를 모두 실행했다. Consumer 지연이나 재시작이 API 요청 접수에 미치는 영향을 줄이고, API와 Consumer의 JVM 및 HikariCP Connection Pool을 분리하기 위해 실행 역할을 나누었다.

**주요 목표:** API RPS 극대화가 아니라 Consumer 장애 시 요청 접수 유지, 별도 프로세스 재시작 및 자원 격리 가능성 검증.

## 2. 아키텍처 변경

### 기존: 단일 프로세스

```text
Spring Boot App (JVM 1개, HikariCP Pool 1개)
  ├─ Notification HTTP API
  ├─ Outbox Publisher
  └─ RabbitMQ Consumer (PUSH, SMS, EMAIL)
       └─ Mock Provider

Shared Infrastructure: MySQL / Redis / RabbitMQ
```

### 변경: 2개 프로세스

```text
API App (JVM 1, HikariCP Pool 1)
  ├─ Notification HTTP API
  └─ Outbox Publisher
          │
          ▼
       RabbitMQ
          │
          ▼
Worker App (JVM 2, HikariCP Pool 2)
  └─ RabbitMQ Consumer (PUSH, SMS, EMAIL)
       └─ Mock Provider

Shared Infrastructure: MySQL / Redis / RabbitMQ
```

동일 Docker 이미지를 재사용하되 실행 환경 변수로 각 프로세스의 역할을 제어했다.

| 역할 | `NOTIFICATION_CONSUMER_ENABLED` | `OUTBOX_PUBLISHER_ENABLED` |
| --- | --- | --- |
| 단일 App | `true` | `true` |
| API App | `false` | `true` |
| Worker | `true` | `false` |

> 두 컨테이너는 같은 Docker 호스트에서 실행한다. 물리 서버가 분리된 것은 아니며 MySQL, Redis, RabbitMQ도 공유한다. API와 Publisher는 여전히 같은 JVM에서 실행한다.

## 3. 장애 격리 실험

- Worker 컨테이너를 중지하고 API에 요청을 가했다.
- **2 VU, 10초:** 2,657건의 HTTP 202 수락, 요청 실패율 0%.
- Worker가 중지된 상태에서도 API는 Notification과 Outbox 저장을 계속 수행할 수 있었다.
- Worker 재시작 후 Consumer가 Queue에 다시 연결되고 적체된 메시지를 처리하는 것을 확인했다.
- 별도의 50 VU 실험에서는 Outbox 36,432건이 모두 `PUBLISHED`, Delivery 36,432건이 모두 `SENT`로 전환되는 것을 확인했다.

**해석:** Worker 프로세스를 중지하더라도 API 접수와 재시작 후 처리 재개가 가능했다. 다만 이는 Mock Provider 및 단일 로컬 Docker 호스트 환경의 검증이며, 물리 장애, MySQL 장애, 실제 외부 Provider의 최종 도달을 보장하지 않는다.

## 4. 정상 부하 성능 비교

### 실험 조건

- k6: **50 VU, 15초**, 요청별 고유 `eventId`, 응답 코드 202 확인.
- 요청: `POST /api/v1/notifications`, `PUSH`, `SMS`, `EMAIL` 채널.
- 각 측정 전 Notification / Delivery / Outbox 테스트 데이터, RabbitMQ 관련 Queue, 전용 Redis 테스트 DB 초기화.
- 컨테이너 재생성, Actuator 준비 상태 확인, 1 VU × 5초 API 워밍업, 워밍업 메시지 처리 완료 확인 후 재초기화.
- 측정 스크립트: `experiments/api-worker-isolation/benchmark.sh`.
- 실행 순서: 단일 → 분리 → 분리 → 단일.

### 측정 결과

| 구분 | 단일 App RPS | 분리 구조 RPS | 단일 App p95 | 분리 구조 p95 |
| --- | ---: | ---: | ---: | ---: |
| 1차 | 418.14 | 286.11 | 254.49 ms | 387.21 ms |
| 2차 | 498.30 | 384.78 | 190.07 ms | 244.97 ms |
| 산술평균 (참고) | **458.22** | **335.45** | **222.28 ms** | **316.09 ms** |

- 두 반복 측정 모두 단일 App의 API RPS와 p95가 우세했다.
- **분리 구조의 정상 상태 처리량 향상은 확인되지 않았다.**
- `HTTP 202`는 비동기 작업의 **접수 성공**을 의미하며 최종 Provider 발송 완료와 다르다.
- 실행별 편차가 존재했고, 반복 횟수 2회 및 측정 시간 15초라는 한계가 있다. 위 차이를 운영 환경의 성능 개선 또는 저하율로 일반화하지 않는다.

### HikariCP 관측

| 실행 구조 | API Pool 최대 Pending | Worker Pool 최대 Pending |
| --- | ---: | ---: |
| 단일 App | 42 | 별도 Pool 없음 |
| 분리 구조 | 41 | 0 |

- 위 값은 1차 실험의 샘플링된 최댓값이며 장시간 최대치나 평균이 아니다.
- Consumer를 분리했어도 API 프로세스의 커넥션 대기는 상당했다.
- API의 Notification, Delivery, Outbox 저장 작업과 동일 JVM의 Publisher가 경합 원인 후보이나, **원인은 확정하지 않았다**.

## 5. 결과 해석 및 트레이드오프

### 얻은 것

- Consumer를 독립적으로 중지하고 재시작하는 실행 단위 확보.
- API와 Worker의 JVM 및 HikariCP Pool 독립화.
- Worker 일시 중단 시 요청 접수 유지와 복구 후 발송 처리 재개 확인.

### 비용 및 한계

- 동일 로컬 호스트에 JVM이 추가되어 CPU, 메모리, GC 부하가 늘어날 수 있음. CPU, 메모리, GC 계측을 하지 않아 성능 차이의 원인으로 단정하지 않음.
- MySQL 등 공유 인프라의 장애는 여전히 API와 Worker 모두에 영향을 줄 수 있음.
- API와 Publisher는 한 프로세스이므로 API Pool의 커넥션 경합이 남아 있음.
- 별도 Outbox Publisher 프로세스를 추가하면 역할 격리는 강화되지만 순차 발행 병목이 자동으로 해결되는 것은 아님.

**설계 결론:** 현 시점에서 2프로세스 구조를 *정상 상태 RPS 개선*의 근거로 주장하지 않는다. 분리의 근거는 Worker의 독립 중단, 복구 및 장애 영향 범위 축소이며, 운영 비용에 비해 가치가 있는지는 추가 부하 격리 실험이 필요하다.

## 6. 남은 검증 과제

1. **Consumer 고부하 격리 실험:** Consumer 부하를 통제하여 단일 App과 분리 구조의 API p95 및 HikariCP 경합 비교.
2. **자원 계측:** Docker 컨테이너별 CPU 및 메모리, JVM GC, DB 커넥션 획득 대기 시간 분석.
3. **처리 완료 지표:** HTTP 202 접수 RPS와 별개로 Outbox 적체, RabbitMQ Queue Lag, Delivery 완료 지연 측정.
4. **동시 중복 처리:** 동일 Delivery 메시지의 병렬 소비에서 외부 Provider 중복 호출 가능성 재현 및 선점/복구 설계.
5. **3프로세스 분리 여부:** Publisher가 API 경합의 원인으로 확인된 경우에만 API / Publisher / Worker 분리 검토.

## 7. 재현 명령어

```bash
# 반드시 이 프로젝트 전용 실험 DB, RabbitMQ, Redis에서만 실행
# benchmark.sh는 데이터 삭제 및 Queue purge를 수행하므로 실행 전 백업 확인
./experiments/api-worker-isolation/benchmark.sh monolith
./experiments/api-worker-isolation/benchmark.sh separated
```

개인정보 또는 테스트 사용자 정보가 포함될 수 있는 SQL 백업은 Git에 포함하지 않는다. k6 및 HikariCP 로그에서 실험별 수치를 검증할 수 있다.
