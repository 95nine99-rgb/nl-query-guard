# 패키지 구조

8/24(월)에 정한다. 한 번 정하면 2주 내내 이 위에 얹는다.

## 결정 요약 (README에 3줄로 옮길 것)

1. 패키지는 **기능 우선**으로 나눈다. 계층 우선(`controller/` `service/` `repository/`)이 아니다
2. 인터페이스는 **구현을 갈아끼우며 비교할 3곳만** 둔다
3. 나머지는 구현체가 하나뿐이라 인터페이스를 파지 않는다

## 구조

```
dev.portfolio.nlquery
├─ common/
│   ├─ config/          DataSource 2개(app, query_ro) 설정
│   ├─ error/           에러코드 enum, 전역 예외 핸들러
│   └─ support/
│
├─ transaction/                    ← 기능 1. 평범한 3계층으로 충분
│   ├─ TransactionController
│   ├─ TransactionService
│   ├─ TransactionRepository
│   ├─ Transaction                 (엔티티)
│   ├─ Category                    (엔티티)
│   └─ dto/
│
└─ nlquery/                        ← 기능 2. 이 프로젝트의 본체
    ├─ NlQueryController
    ├─ NlQueryService              파이프라인 오케스트레이션
    │
    ├─ intent/
    │   ├─ IntentClassifier        ← 포트
    │   ├─ RuleIntentClassifier    8/25
    │   └─ LlmIntentClassifier     8/28
    │
    ├─ sql/
    │   ├─ SqlBuilder              SELECT 구조는 서버가 고정
    │   ├─ SqlValidator            ← 포트
    │   ├─ JSqlParserValidator     8/26
    │   └─ ValidationResult        (pass, reason)
    │
    ├─ execution/
    │   └─ SafeQueryExecutor       읽기전용 DataSource + rowLimit + timeout
    │                              user_id 강제 주입도 여기
    ├─ llm/
    │   ├─ LlmClient               ← 포트
    │   ├─ MockLlmClient           8/28, 이후 1주차 내내 이것만
    │   └─ OpenAiLlmClient         8/28부터
    │
    └─ log/
        ├─ NlQueryLog              (엔티티)
        └─ NlQueryLogRepository
```

## 왜 이 모양인가

### 1. `nlquery/` 안이 파이프라인 단계로 나뉘어 있다

`intent → sql → execution`. **패키지 트리만 봐도 파이프라인이 읽힌다.**
면접관이 README보다 먼저 여는 게 패키지 구조인데, 여기서 이미 설계가 보인다.
계층으로 나눴으면 이 효과가 없다.

### 2. 인터페이스를 딱 3곳만 뒀다

셋 다 **구현을 갈아끼우면서 비교할 지점**이라는 공통점이 있다.

| 포트 | 갈아끼우는 이유 | 언제 |
|---|---|---|
| `IntentClassifier` | 규칙 기반 vs LLM 기반 비교 | 8/28 |
| `SqlValidator` | 정규식 vs JSQLParser 비교 → ADR-002 | 8/26~8/27 |
| `LlmClient` | Mock vs 실제. **8/24~8/27을 LLM 없이 완주하는 계획 자체가 이것 때문에 가능하다** | 8/28 |

**"인터페이스를 왜 여기만 뒀냐"에 위 표로 답할 수 있으면 그게 설계 능력이다.**
헥사고날을 전면 도입한 것보다 이게 훨씬 세게 먹힌다.

## 안 하는 것

| | 왜 |
|---|---|
| `controller/` `service/` `repository/` 3분할 | 기능 하나 고치려면 세 폴더를 왔다 갔다. 커질수록 무너진다 |
| `domain/` `application/` `adapter/` 전면 헥사고날 | 2주 규모에 과하다. 파일만 3배 되고 "왜?"에 답 못 하면 감점 |
| `XxxService` + `XxxServiceImpl` | 구현체가 하나뿐인데 인터페이스를 파는 건 관성. **생각 안 하고 관례 따라 했구나가 읽힌다** |
| 멀티 모듈 Gradle | 지금 규모에서 얻는 게 없다 |

## 되돌릴 조건

`nlquery/` 하위 패키지가 7개를 넘어가면 파이프라인이 아니라 잡동사니가 된 것이다.
그때는 단계를 합치거나 별도 기능 패키지로 승격한다.
