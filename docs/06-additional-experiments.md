# API / Worker 실행 환경 분리 및 검증

## 1. 배경 및 설계

기존에는 하나의 Spring Boot JVM에서 HTTP API, Outbox Publisher, RabbitMQ Consumer를 실행했다. Consumer 장애가 API에 미치는 영향을 줄이기 위해 API와 Worker를 별도 프로세스로 분리했다.

| 구성 | API App | Worker |
|---|---|---|
| 기존 | API + Publisher + Consumer | 없음 |
| 변경 | API + Publisher | Consumer |

- 동일한 Docker 이미지 사용, 환경 변수로 실행 역할 제어
- JVM 및 HikariCP Connection Pool 독립화
- MySQL, Redis, RabbitMQ는 공유
- 동일 Docker 호스트의 8 CPU 환경에서 검증

## 2. 장애 격리 검증

Worker 중단 상태에서 **2 VU, 10초 동안 2,657건의 API 요청을 모두 HTTP 202로 접수**했다.

Worker 재시작 후 적체된 메시지의 처리 재개도 확인했다.

**결과:** Worker의 독립적인 중단 및 복구가 가능하고, Worker 장애 중에도 API 요청 접수를 유지할 수 있음을 검증했다.

## 3. 정상 부하 성능 비교

**조건:** 50 VU, 15초, 구조별 2회. DB, RabbitMQ, Redis 초기화 및 JVM 워밍업 후 측정.

| 지표 | 단일 App | 분리 구조 |
|---|---:|---:|
| 평균 RPS | **458.22** | 335.45 |
| 평균 p95 | **222.28ms** | 316.09ms |
| HTTP 실패율 | 0% | 0% |

정상 부하에서는 단일 App이 더 높은 API 접수 처리량을 기록했다. 분리 구조의 성능 개선은 확인되지 않았다.

## 4. 성능 저하 원인 분석

요청 완료 속도에 따른 부하 차이를 제거하기 위해 **200 RPS, 30초**의 고정 요청률로 네 가지 구성을 비교했다.

| 구성 | API p95 | 평균 API Pending |
|---|---:|---:|
| 단일 App | 54.19ms | 1.80 |
| API / Worker 분리 | 104.58ms | 1.45 |
| API 단독 (Worker 중단) | **31.07ms** | **0.28** |
| Worker Idle (Consumer 비활성화) | 65.93ms | 0.86 |

모든 구성에서 약 6,000건을 처리했으며 HTTP 실패율은 0%였다.

**분석 결과**

- Worker 중단 시 API 지연 감소: Consumer 처리 작업에 따른 공유 자원 경합 가능성
- Worker Idle에서도 지연 증가: 추가 JVM의 실행 비용 또는 환경 영향 가능성
- 단일 App과 분리 구조의 API Pending 유사: Connection Pool 대기 개수만으로 성능 차이 설명 불가
- CPU, GC, DB 잠금 및 쿼리 수행시간에 대한 추가 계측이 없어 정확한 원인과 기여도는 확정하지 못함

각 고정 요청률 실험은 1회 측정이며, Consumer 비활성화 구성에서는 실제 발송이 이루어지지 않으므로 전체 알림 처리 성능과 직접 비교할 수 없다.

## 5. 결론 및 후속 과제

**설계 판단:** API/Worker 분리는 정상 상태의 처리량 개선보다 장애 격리와 독립적인 실행 및 복구에 의미가 있었다.

다만 동일 호스트에서 프로세스 추가에 따른 운영 비용과 API 응답 지연이 관측됐으므로, 운영 환경 적용 여부는 추가 검증이 필요하다.

**후속 과제:** Consumer 고부하 격리 검증, JVM 및 DB 병목 분석, 동시 중복 메시지 처리 방지.

**재현:** `experiments/api-worker-isolation/benchmark.sh`

측정값은 로컬 Mock Provider 및 테스트 환경에 한정하며, HTTP 202는 알림 발송 완료가 아닌 접수 성공을 의미한다.