# API / Worker 실행 환경 분리 및 검증

## 1. 배경 및 설계

기존 단일 Spring Boot JVM에서 실행하던 API, Outbox Publisher, RabbitMQ Consumer를 API와 Worker 프로세스로 분리했다.

| 구성 | API App | Worker |
|---|---|---|
| 기존 | API + Publisher + Consumer | 없음 |
| 변경 | API + Publisher | Consumer |

- 동일 Docker 이미지 사용, 환경 변수로 실행 역할 제어
- JVM 및 HikariCP Connection Pool 독립화
- MySQL, Redis, RabbitMQ는 공유
- 동일 Docker 호스트의 8 CPU 환경에서 검증

**목표:** 처리량 향상보다 Worker 장애 시 API 접수 유지 및 독립적인 복구 가능성 검증

## 2. 장애 격리 검증

Worker 중단 상태에서 **2 VU, 10초 동안 2,657건의 요청을 모두 HTTP 202로 접수**했다. Worker 재시작 후 적체된 메시지의 처리 재개도 확인했다.

**결과:** Worker 장애 중에도 API 요청 접수를 유지하고, 독립적인 재시작 및 처리가 가능함을 검증했다.

## 3. 정상 부하 성능 비교

**조건:** 50 VU, 15초, 구조별 2회 측정. 테스트 데이터 초기화 및 워밍업 후 실행.

| 지표 | 단일 App | 분리 구조 |
|---|---:|---:|
| 평균 RPS | **458.22** | 335.45 |
| 평균 p95 | **222.28ms** | 316.09ms |
| HTTP 실패율 | 0% | 0% |

단일 App이 더 높은 API 접수 처리량을 기록했으며, 프로세스 분리에 따른 성능 개선은 확인되지 않았다.

## 4. 성능 저하 원인 분석

### 4.1 고정 요청률 실험

**조건:** 200 RPS, 30초, 각 구성 1회 측정.

| 구성 | API p95 | 평균 API Pending |
|---|---:|---:|
| 단일 App | 54.19ms | 1.80 |
| API / Worker 분리 | 104.58ms | 1.45 |
| API 단독 | **31.07ms** | **0.28** |
| Worker Idle | 65.93ms | 0.86 |

Worker 중단 시 API 지연이 감소했고, Consumer를 비활성화한 Worker JVM만 실행해도 지연이 증가했다. 반면 HikariCP Pending 개수만으로는 구조별 성능 차이를 설명하기 어려웠다.

### 4.2 Consumer 동시성 및 DB 경합 분석

분리 구조에서 Consumer Concurrency를 5에서 1로 변경하고 동일한 200 RPS, 30초 부하를 적용했다.

| 지표 | Concurrency 5 | Concurrency 1 |
|---|---:|---:|
| API p95 | 165.38ms | **52.88ms** |
| Connection 획득 평균 | 14.231ms | **1.255ms** |
| Connection 사용 평균 | 13.084ms | **8.257ms** |
| MySQL 행 잠금 대기 횟수 | 299회 | **2회** |
| MySQL 행 잠금 총 대기시간 | 3,183ms | **14ms** |
| HTTP 실패율 | 0% | 0% |

Consumer 동시성을 낮추자 MySQL 행 잠금 대기와 API의 DB Connection 획득 시간이 함께 감소했고, **API p95는 약 68% 개선**됐다.

이는 Consumer의 동시 DB 작업이 공유 MySQL의 경합과 API 지연에 기여했을 가능성을 뒷받침한다. GC 정지시간은 분리 구조의 API JVM에서 증가하지 않아 주요 원인으로 볼 근거가 부족했다.

단, MySQL 잠금 지표는 서버 전체 기준이며, Consumer 동시성 변경에 따라 발송 처리량과 메시지 적체량도 달라진다. 각 조건의 반복 측정도 부족하므로 정확한 인과관계와 최적 동시성은 확정하지 않았다.

## 5. 결론

**API / Worker 분리는 성능 최적화보다 장애 격리 목적에서 유효한 설계였다.**

- Worker 독립 중단 및 재시작, API 요청 접수 유지 확인
- 분리 구조에서 API 성능 저하 관측
- Consumer 동시성 조절로 DB 경합과 API 지연이 함께 감소하는 현상 확인

**후속 과제:** Worker 처리량과 Queue 적체 측정, 동시 중복 메시지 처리 방지.

**재현:** `experiments/api-worker-isolation/benchmark.sh`

모든 결과는 로컬 Mock Provider 환경에 한정되며, HTTP 202는 알림 발송 완료가 아닌 접수 성공을 의미한다.