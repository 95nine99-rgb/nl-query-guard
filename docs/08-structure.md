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

## Lombok — 쓰는 것과 안 쓰는 것

**쓴다. 실무 표준이고 안 쓸 이유가 없다.**
다만 **엔티티에는 두 개만** 붙인다.

```java
@Entity
@Table(name = "transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction { ... }
```

| 어노테이션 | 판단 | 이유 |
|---|---|---|
| `@Getter` | 채택 | 반복만 줄인다. 부작용 없음 |
| `@NoArgsConstructor(PROTECTED)` | 채택 | JPA 가 리플렉션으로 객체를 만들 때 기본 생성자가 필요하다. `PROTECTED` 로 막아 **외부에서 빈 객체를 못 만들게** 한다 |
| `@Setter` | **버림** | 아무 데서나 값이 바뀐다. 생성자로만 값을 넣겠다는 의도가 깨진다 |
| `@Data` | **버림** | `@Setter` + `@EqualsAndHashCode` 가 딸려온다. 엔티티에서 `equals/hashCode` 를 잘못 쓰면 연관관계 순회 중 **무한루프**가 난다 |
| `@AllArgsConstructor` | **버림** | 필드 순서가 바뀌면 컴파일은 되는데 값이 뒤바뀐다. **조용히 깨지는 게 제일 나쁘다** |
| `@ToString` | **버림** | 연관 필드까지 출력하려다 **LAZY 로딩을 전부 터뜨린다.** 로그 한 줄 찍으려다 N+1 이 나는 흔한 사고 |

### 왜 굳이 갈랐나

"Lombok 씁니다" 는 아무 정보도 아니다. 다들 쓴다.
**어디까지 쓰고 어디서 멈췄는지**가 판단이다.

여기서 버린 4개는 전부 **편해지는 대신 통제를 잃는 것들**이다.
이 프로젝트의 주제가 "무엇을 통과시킬지 내가 정한다" 인데, 엔티티에서 그걸 놓으면 앞뒤가 맞지 않는다.

### 필드 타입

| 필드 | 타입 | 이유 |
|---|---|---|
| `id` | `Long` | `BIGINT` 대응. 저장 전엔 `null` 이어야 "아직 저장 안 됨" 이 구분된다. `long` 이면 기본값 `0` 이라 Hibernate 가 헷갈린다 |
| `amount` | `int` | SQL 이 `NOT NULL`. 원시 타입이면 애초에 null 이 불가능하다. **제약을 코드에도 반영한다** |
| `transactionAt` | `LocalDateTime` | `DATETIME` 대응. `TIMESTAMP` 가 아니라 타임존 변환이 없다 (ADR-003) |

`final` 은 쓸 수 없다. `id` 는 `AUTO_INCREMENT` 라 저장 시점에 DB 가 채운다.
불변성은 **setter 를 만들지 않는 것**으로 확보한다.

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
