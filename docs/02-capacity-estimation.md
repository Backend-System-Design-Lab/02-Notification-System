# 02. Capacity Estimation

## 1. 기본 가정

| 항목            |             값 | 근거               |
|---------------|--------------:|------------------|
| Push 알림       | 10,000,000건/일 | 요구사항             |
| SMS 알림        |  1,000,000건/일 | 요구사항             |
| Email 알림      |  5,000,000건/일 | 요구사항             |
| 총 알림 요청       | 16,000,000건/일 | 세 채널 합계          |
| Push 평균 등록 단말 |            2대 | 다중 단말을 고려한 가정    |
| 피크 계수         |            5배 | 특정 시간대 트래픽 집중 가정 |

## 2. 트래픽 산정

### 평균 RPS

```text
16,000,000 ÷ 86,400 
≈ 185 RPS
```

채널별 평균 처리량:

```text
Push ≈ 116 RPS 
SMS ≈ 12 RPS 
Email ≈ 58 RPS
```

### 피크 RPS

```text
185 × 5 
≈ 925 RPS
```

따라서 알림 API는 약 **1,000RPS** 수준의 피크 요청을 기준으로 설계한다.

## 3. 실제 Delivery 처리량

Push는 한 사용자가 여러 단말을 가질 수 있으므로 알림 요청 수와 실제 발송 횟수가 다르다.

평균 2개의 Push 단말을 가정하면:

```text
Push 10M x 2 = 20M deliveries/day
SMS          = 1M deliveries/day
Email        = 5M deliveries/day

총 Delivery = 26M/day
평균 ≈ 301 deliveries/sec 
피크 ≈ 1,500 deliveries/sec
```
따라서 시스템에서는 **Notification RPS**와 **실제 Delivery 처리량**을 분리하여 고려한다.

## 4. 저장 용량

알림 요청과 전송 결과를 합쳐 평균 약 1KB가 저장된다고 단순 가정한다.

```text
16M × 1KB 
≈ 16GB/day 

16GB × 365 
≈ 5.8TB/year
```

실제 저장량은 메시지 본문 저장 여부, Delivery Log, 인덱스 및 보관 기간에 따라 증가할 수 있다.

## 5. 캐시 

주요 캐시 대상:
* 사용자 연락처 정보
* Device Token
* 알림 On/Off 설정
* 알림 템플릿

정확한 캐시 크기와 TTL은 구현 및 실험 단계에서 결정한다.

## 6. 메시지 처리량

비동기 메시지 큐 도입 시 처리해야 하는 부하는 다음을 기준으로 한다.

* 알림 요청: 최대 약 **925 msg/s**
* 실제 Delivery 기준: 최대 약 **1,500 msg/s**
* 높은 부하에서 약간의 Queue 지연은 허용
* Consumer는 수평 확장 가능하도록 설계

## 7. 예상 병목

| 컴포넌트           | 예상 병목           | 확인 지표               | 대응 방법         |
| -------------- |-----------------|---------------------|---------------|
| Application    | 요청 증가           | RPS, CPU, Thread    | 수평확장          |
| Database       | 사용자/설정 조회 증가    | Connection, Query   | Redis Cache   |
| Message Broker | Consumer 처리량 증가 | Queue Depth         | Consumer 확장   |
| External API   | 지연 및 장애         | Latency, Error Rate | 비동기 처리, Retry |

## 8. 산정 결과 요약

| 항목                  |       예상값 |
|---------------------|----------:|
| 일일 Notification     |       16M |
| 평균 Notification RPS |     약 185 |
| 피크 Notification RPS |     약 925 |
| 일일 Delivery         |     약 26M |
| 피크 Delivery 처리량     | 약 1,500/s |
| 연간 저장량 | 약 5.8TB |

````