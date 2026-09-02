# 측정 결과

> 실제 수치만 적는다. **추측으로 채우지 않는다.** 확인 못 한 값은 "미확인".

## 측정 환경

| 항목 | 값 |
|---|---|
| 머신 | MacBook Air (Apple Silicon) |
| MySQL | 8.0.46 (Docker, `mysql:8.0`) |
| JDK | Temurin 21.0.12.1 LTS |
| Spring Boot | 4.1.0 |
| 데이터 | transactions 5,000건 / users **3명** / categories 5종 |
| 측정일 | 2026-08-31 |
| 측정 도구 | `EXPLAIN`, `EXPLAIN ANALYZE` |

> ⚠️ **users 3명은 현실적이지 않다.** 이 한계가 아래 결과를 갈랐다. 4절 참조.

---

## 1. 복합 인덱스 — 예상과 다른 결과가 나왔다

### 대상 쿼리

```sql
SELECT * FROM transactions
WHERE user_id = 1 AND category_id = 1
  AND transaction_at BETWEEN '2026-08-01 00:00:00' AND '2026-09-01 00:00:00'
ORDER BY transaction_at DESC
LIMIT 20;
```

### 1-1. before — 복합 인덱스 없음

인덱스: `PRIMARY`, `fk_tx_user(user_id)`, `fk_tx_category(category_id)` (FK 자동 생성분)

```
key            fk_tx_category
key_len        8
rows           995
filtered       3.65%
Extra          Using where; Using filesort
```

**995행을 읽어 3.65%(약 36행)만 쓴다.** 그리고 `Using filesort` — 정렬을 별도로 한다.

### 1-2. 인덱스 추가

```sql
CREATE INDEX idx_tx_user_cat_time ON transactions (user_id, category_id, transaction_at);
```

**부수 효과: `fk_tx_user` 인덱스가 사라졌다.**
복합 인덱스의 선두 컬럼이 `user_id` 라 MySQL 이 중복으로 보고 정리했다.
FK 제약(`CONSTRAINT fk_tx_user`)은 그대로 살아 있고 인덱스만 흡수됐다.

### 1-3. after — **옵티마이저가 새 인덱스를 고르지 않았다**

`ANALYZE TABLE` 로 통계를 갱신한 뒤에도 결과가 같았다.

```
possible_keys  fk_tx_category, idx_tx_user_cat_time   ← 후보에는 들어옴
key            fk_tx_category                          ← 그런데 안 고름
rows           995
Extra          Using where; Using filesort
```

**여기서 실험이 끝났으면 "인덱스가 소용없었다" 로 잘못 결론 낼 뻔했다.**

### 1-4. `FORCE INDEX` 로 실측 비교

`EXPLAIN ANALYZE` 로 **추정이 아닌 실제 실행 시간**을 쟀다.

**기본 (옵티마이저 선택 = `fk_tx_category`)**
```
-> Limit: 20 row(s)                                    (actual time=1.62..1.62 rows=20)
  -> Sort: transaction_at DESC, limit 20               (actual time=1.61..1.62 rows=20)
    -> Filter: user_id=1 and transaction_at between..  (actual time=0.595..1.54 rows=319)
      -> Index lookup using fk_tx_category             (actual time=0.227..1.05 rows=995)
```

**강제 (`FORCE INDEX (idx_tx_user_cat_time)`)**
```
-> Limit: 20 row(s)                                    (actual time=1.11..1.2 rows=20)
  -> Index range scan using idx_tx_user_cat_time
     over (user_id=1 AND category_id=1 AND transaction_at between ..) (reverse)
                                                       (actual time=1.11..1.2 rows=20)
```

| 지표 | 기본 | 강제 (복합 인덱스) | 차이 |
|---|---|---|---|
| **읽은 행** | **995** | **20** | **50배** |
| 필터 후 행 | 319 | — (필터 단계 없음) | |
| **정렬** | **`Sort` 단계 존재** | **없음** | 단계 제거 |
| 실제 시간 | 1.62 ms | **1.20 ms** | 26% |

### 1-5. 무엇이 증명됐나

**`Sort` 단계가 통째로 사라졌다.** 실행 계획에서 `(reverse)` 가 핵심이다.
인덱스가 `(user_id, category_id, transaction_at)` 순서라, 앞 두 컬럼이 등가조건으로 고정되면
세 번째 컬럼이 **이미 정렬된 상태**로 저장돼 있다. 역순으로 읽기만 하면 `ORDER BY ... DESC` 가 해결된다.

`docs/03-erd.md` 에 적은 **"등가조건 → 등가조건 → 범위·정렬"** 순서의 근거가 이걸로 확인됐다.

---

## 2. 예상과 달랐던 지점

**예상**: 인덱스를 만들면 옵티마이저가 자동으로 쓰고 `filesort` 가 사라진다.
**실제**: 인덱스를 만들어도 **옵티마이저가 고르지 않았다.** 강제해야 효과가 나왔다.

### 왜 안 골랐나

`SHOW INDEX` 의 카디널리티를 보면 답이 나온다.

| 인덱스 | 컬럼 | 카디널리티 |
|---|---|---|
| `idx_tx_user_cat_time` | `user_id` (1번째) | **3** |
| | `category_id` (2번째) | 15 |
| | `transaction_at` (3번째) | 4,982 |
| `fk_tx_category` | `category_id` | 5 |

**`user_id` 의 카디널리티가 3이다.** 시드에 유저를 3명만 넣었기 때문이다.

옵티마이저 입장에서 복합 인덱스는 **"선두 컬럼이 3가지 값뿐인 인덱스"** 로 보인다.
선두 컬럼의 선택도가 낮으면 인덱스 값어치가 떨어진다고 계산한다.
그래서 단일 컬럼 인덱스인 `fk_tx_category` 를 골랐다.

**옵티마이저는 추정 비용으로 판단한다. 추정이 틀릴 수 있다.**

---

## 3. 시간 차이가 26% 밖에 안 난 이유

읽는 행이 50배 줄었는데 시간은 1.62 ms → 1.20 ms 였다.

**5,000행이 전부 InnoDB 버퍼 풀에 들어가기 때문이다.**
디스크 I/O 가 발생하지 않으므로 995행을 읽든 20행을 읽든 비용 차이가 작다.

데이터가 버퍼 풀을 넘어가면 이 격차가 벌어질 것으로 예상되나 **이번 측정에서는 확인하지 않았다.**

---

## 4. 이번 측정의 한계 (알고 있다)

| 한계 | 영향 |
|---|---|
| **users 3명** | `user_id` 카디널리티 3 → 옵티마이저가 복합 인덱스를 기피. 실제 서비스라면 반대로 나왔을 가능성이 크다 |
| 데이터 5,000건 | 전부 메모리에 상주. 디스크 I/O 차이가 드러나지 않음 |
| 단일 실행 | 반복 측정·중앙값이 아니라 1회 실행값 |
| 동시성 없음 | 단일 커넥션. 락 경합·버퍼 풀 경쟁이 없는 조건 |

**첫 번째가 결과를 갈랐다.** 시드 설계가 실험 결론에 직접 영향을 준 사례다.

### 후속으로 할 수 있는 것 (하지 않음)
- users 를 100명 이상으로 늘려 카디널리티를 올린 뒤 옵티마이저가 복합 인덱스를 자동 선택하는지 재확인
- 데이터를 50만 건으로 늘려 버퍼 풀을 넘긴 상태에서 재측정
- 3회 이상 반복해 중앙값 산출

**3일 일정 안에서 우선순위가 아니라고 판단해 미뤘다.** 여기 적어두는 것으로 대신한다.

---

## 5. 이 실험에서 배운 것

1. **`EXPLAIN` 은 추정이고 `EXPLAIN ANALYZE` 는 실측이다.** 추정만 보고 "인덱스가 소용없다" 고 결론 낼 뻔했다
2. **옵티마이저가 인덱스를 안 쓰는 것과 인덱스가 쓸모없는 것은 다르다.** 강제해서 재보기 전에는 알 수 없다
3. **측정 환경(시드 데이터)이 결론을 바꾼다.** 유저 3명이라는 설정 하나가 옵티마이저 판단을 뒤집었다
4. 복합 인덱스의 **컬럼 순서**가 `ORDER BY` 처리 방식을 결정한다. `(reverse)` 스캔으로 정렬 단계 자체가 사라졌다

---

## 6. 검증 레이어 차단율 — v0.1-unguarded (파서 없음)

**측정일** 2026-08-31 / **도구** `scripts/run-attacks.sh` / **데이터** transactions 5,000건

파서를 한 줄도 짜기 전에 먼저 쟀다. **무엇을 막아야 하는지 모르고 막는 코드를 짤 수 없다.**

### 6-1. 계정별 차단 결과

| # | 유형 | 실행 SQL | `app` | `query_ro` |
|---|---|---|---|---|
| 1 | DROP | `DROP TABLE transactions` | ❌ **테이블 소멸** | ✅ 차단 |
| 2 | DELETE | `DELETE FROM transactions` | ❌ **5,000행 삭제** | ✅ 차단 |
| 3 | UPDATE | `UPDATE transactions SET amount=0` | ❌ **5,000행 변조** | ✅ 차단 |
| 4 | 다중 구문 | `SELECT ...; DROP TABLE users` | ✅ 차단 | ✅ 차단 |
| 5 | 주석 우회 | `... WHERE user_id=1 -- AND category_id=1` | ❌ 1,632건 | ❌ 200건 |
| 6 | UNION 인젝션 | `... UNION SELECT id,email,... FROM users` | ❌ **5,003건 (이메일 유출)** | ✅ 차단 |
| 7 | 다른 user_id | `... WHERE user_id = 2` | ❌ 1,649건 | ❌ 200건 |
| 8 | 시스템 테이블 | `SELECT table_name FROM information_schema.tables` | ❌ 90건 | ❌ 89건 |
| 9 | 서브쿼리 | `... WHERE user_id IN (SELECT id FROM users)` | ❌ 5,000건 | ✅ 차단 |
| 10 | 자원 고갈 | `SELECT SLEEP(10)` | ❌ 통과 | ✅ 차단 (타임아웃) |
| 11 | 대소문자 우회 | `SeLeCt * FrOm transactions` | ❌ 5,000건 | ❌ 200건 |
| 12 | 주석 분할 | `SEL/**/ECT * FROM transactions` | ✅ 차단 | ✅ 차단 |
| | **차단 합계** | | **2 / 12** | **8 / 12** |

**오탐 검사 (정상 질의 3종)**

| # | 질의 | `app` | `query_ro` |
|---|---|---|---|
| N1 | 카테고리별 합계 | ✅ 통과 | ✅ 통과 |
| N2 | 비싼 거래 5건 | ✅ 통과 | ✅ 통과 |
| N3 | 카테고리별 그룹 합계 | ✅ 통과 | ✅ 통과 |

**오탐 0건.** 다만 아직 검증 로직이 없으므로 당연한 결과다. 파서를 붙인 뒤 다시 재야 의미가 생긴다.

### 6-2. 파서 0줄로 8/12 가 막혔다

읽기 전용 계정 하나로 **12종 중 8종**이 막혔다. 코드는 한 줄도 짜지 않았다.

- **쓰기 계열(1·2·3)** — `GRANT SELECT` 뿐이라 거부
- **`users` 참조(6·9)** — 테이블 권한 없음
- **자원 고갈(10)** — `queryTimeout=3s`
- **다중 구문(4)** — JDBC `allowMultiQueries=false` 기본값
- **주석 분할(12)** — MySQL 파서가 키워드로 인식하지 않음

> ⚠️ **4번과 12번은 내가 만든 방어가 아니다.** JDBC·MySQL 기본 동작이 우연히 막았다.
> "차단됨" 을 내 성과로 적으면 안 된다. 기본값이 바뀌면 그대로 열린다.

### 6-3. 뚫린 4종 — DB 가 막을 수 없는 것들

| # | 뚫린 이유 | 담당 방어층 |
|---|---|---|
| 5 주석 우회 | `transactions` 를 `SELECT` 한다. **권한 안에 있다** | 3 (user_id 주입) |
| 7 다른 user_id | 위와 같음 | 3 (user_id 주입) |
| 11 대소문자 우회 | 위와 같음 | 3 (user_id 주입) |
| 8 시스템 테이블 | `information_schema` 는 **GRANT 와 무관하게 접근 가능** | 2 (테이블 화이트리스트) |

**DB 는 "무엇을" 만 안다. "누구 것인지" 는 앱만 안다.**
`SELECT * FROM transactions WHERE user_id = 2` 는 DB 입장에서 완전히 정상 요청이다.
권한 경계는 구조상 앱에서만 강제할 수 있다.

**이것이 파서와 `user_id` 주입이 존재해야 하는 이유다.** 추측이 아니라 측정으로 나온 정의다.

### 6-4. 부수 관찰

**`rows=200` 상한이 작동했다.**
5·7·11 은 `query_ro` 에서도 통과했지만 결과가 200건으로 잘렸다(`maxRows=200`).
**차단은 아니지만 유출 규모를 줄였다.** 완전 차단과 피해 축소는 다른 층이다.

**같은 방어가 두 경로에 다르게 걸려 있었다.**
10번 `SLEEP(10)` 이 `query_ro` 에서만 막혔다. `queryJdbcTemplate` 에만 타임아웃을 걸고
`appJdbcTemplate` 에는 걸지 않았기 때문이다. **설정 누락이 측정으로 드러났다.**

### 6-5. 측정 설계 실수 — 처음 결과는 오염됐다

첫 실행에서 1번(`DROP`)을 맨 앞에 뒀다. 테이블이 사라진 뒤 2~9번이 전부
`TABLE_NOT_FOUND` 로 나왔고, **이걸 "차단" 으로 읽을 뻔했다.**

```
1  DROP    통과(뚫림)         ← 여기서 테이블 소멸
2~9        TABLE_NOT_FOUND   ← 차단이 아니라 대상 소멸
```

그대로 기록했으면 **차단율 92% 라는 가짜 결과**가 나왔을 것이다.

`04-experiment.md` 에 **"통제 변수: 동일 시드 데이터"** 를 미리 적어둔 덕에 잡았다.
측정 전에 설계를 쓰는 이유가 이것이다.

→ 수정: 비파괴 테스트를 먼저, 파괴 테스트를 맨 뒤로 보내고 **파괴 후 매번 복구**한다.
   (`scripts/run-attacks.sh` 주석 참조)

### 6-6. 여기서 나온 판단 재료

**파서가 막아야 할 것은 12종이 아니라 4종이다.** 그중 3종은 `user_id` 강제 주입으로 해결된다.
순수하게 파서(구문·스키마 검증)가 필요한 것은 **8번 하나**다.

그러면 판단 ⑥(정규식 vs JSQLParser)의 전제가 달라진다.

**다만 함정이 있다.** 8/12 차단은 `query_ro` 계정 설정에 전적으로 의존한다.
2026-08-27 시점에 이 계정은 `GRANT USAGE` 뿐이었다(스크립트 미실행).
**그 상태였다면 8종이 한꺼번에 열려 있었다.**

→ **DB 권한 하나에 8종을 걸어둘 것인가**가 진짜 판단이다. 미결.

---

## 7. N+1 (categoryName)

> 의도적으로 남긴 상태. **미측정.** `logs/2026-08-26.md` 참조

| | before | after (fetch join) |
|---|---|---|
| 조회 1회당 SELECT 수 | 미측정 | 미측정 |

---

## 8. user_id 강제 주입 — 정규식 vs JSQLParser (2026-09-01)

**측정 방법**: 동일한 테스트 7개를 두 구현에 각각 돌린다.
`UserIdEnforcer` 인터페이스로 받아 구현만 갈아끼웠다. 테스트 코드는 한 줄도 안 바꿨다.

```java
private final UserIdEnforcer enforcer = new RegexUserIdEnforcer();  // → JsqlUserIdEnforcer
```

**재현**: `./gradlew test --tests "*UserIdEnforcerTest*" --rerun-tasks`
**환경**: Temurin JDK 21.0.12.1 / JSQLParser 4.9 / Spring 미기동(순수 단위 테스트, DB 불필요)

### 8-1. 결과

| # | 케이스 | 입력 SQL | 정규식 | JSQLParser |
|---|---|---|---|---|
| A | 다른 user_id 명시 | `... WHERE user_id = 2` | ✅ 치환 | ❌ `user_id=1 AND user_id=2` |
| B | WHERE 절 없음 | `SeLeCt * FrOm transactions` | ✅ 예외 | ❌ 예외 없이 WHERE 추가 |
| C | 주석으로 뒤가 잘림 | `... WHERE user_id=1 -- AND category_id=1` | ✅ | ✅ |
| D | user_id 조건 없음 | `... WHERE category_id=3 ORDER BY id` | ✅ 삽입 | ✅ |
| E | 문자열 안에 `--` | `... WHERE title = '스타벅스--강남점'` | ❌ **SQL 파손** | ✅ |
| F | 블록 주석 | `... WHERE /* 무시 */ user_id=2` | ⚠️ | ❌ `user_id=1 AND user_id=2` |
| G | 소문자 `where` | `... where user_id = 2` | ❌ **오탐(거부)** | ✅ |
| | **통과** | | **4 / 7** | **4 / 7** |

### 8-2. 숫자는 같지만 실패의 성격이 다르다

**정규식이 실패한 것**

- **E** — `replaceAll("--.*", "")` 가 문자열 리터럴 안의 `--` 까지 잘라 SQL 이 부서졌다.
  `WHERE title = '스타벅스` 로 끝나 따옴표가 닫히지 않는다. **실행하면 문법 오류.**
- **G** — `sql.matches(".*WHERE.*")` 가 소문자 `where` 를 못 잡아 "WHERE 절이 없다" 예외를 던졌다.
  **정상 SQL 을 거부하는 오탐이다.**

**JSQLParser 가 실패한 것**

- **A·F** — `AndExpression` 이 기존 조건을 지우지 않고 묶는다.
  결과: `WHERE user_id = 1 AND user_id = 2` → **0건 반환. 데이터는 새지 않는다.**
- **B** — 파서는 WHERE 가 없어도 만들어 붙일 수 있으므로 예외를 던지지 않는다. **정책 차이지 결함이 아니다.**

> **정규식은 망가지고, 파서는 보수적으로 동작한다.**
> 정규식 실패는 앱이 깨지거나 정상 요청을 막는다. 파서 실패는 결과가 0건이 될 뿐이다.
> **차단율 4/7 이라는 같은 숫자가 같은 의미가 아니다.**

### 8-3. 고치는 난이도도 다르다

| 실패 | 고치는 법 | 난이도 |
|---|---|---|
| 파서 A·F | 기존 `user_id` 비교를 AST 에서 찾아 제거 후 주입 | 중 |
| 파서 B | WHERE 없을 때 예외를 던지도록 정책 통일 | 하 (분기 1개) |
| 정규식 E | 문자열 리터럴 안인지 구분 → **결국 파싱이 필요** | 상 |
| 정규식 G | `(?i)` 추가로 일부 해결. 단 다음 변형이 또 나온다 | 중 |

**정규식 E 를 제대로 고치려면 파서를 써야 한다.** 이것이 결정적이다.

### 8-4. 코드 복잡도

| | 분기 수 | 문자열 조작 | 새 케이스가 늘면 |
|---|---|---|---|
| 정규식 | if 3개 + `replaceAll` 2회 | 있음 | **분기가 하나씩 늘어난다** |
| JSQLParser | if 2개 | 없음 (객체 조립) | 구조를 다루므로 늘지 않는다 |

오늘 정규식 버전은 이 순서로 커졌다.

```
공백 변형 발견   → \s* 추가
주석 우회 발견   → replaceAll("--.*") 추가
user_id 없음 발견 → 분기 추가
문자열 리터럴 발견 → ??? (해결 못 함)
```

**하나 막으면 다음 것이 나왔다.** 파서는 `parse()` 한 번으로 이 변형들이 사라진다.

### 8-5. 부수 발견 — 테스트가 구현 세부에 묶여 있었다

정규식에서 파서로 갈아끼우자 **A·C·D·G 가 한꺼번에 실패**했다. 기능 문제가 아니라 공백 때문이었다.

```
정규식 출력:  WHERE user_id=1 AND category_id = 3
파서 출력:    WHERE user_id = 1 AND category_id = 3
```

`contains("user_id=1")` 이 파서 출력에서는 매칭되지 않았다.
→ 검증 전에 공백을 제거해 **형태가 아닌 내용만 비교**하도록 고쳤다.

```java
String normalized = actual.replaceAll("\\s+", "");
```

**교훈**: 두 구현을 같은 테스트로 비교하려면 검증이 출력 형식에 의존하면 안 된다.

### 8-6. 또 하나 — 가짜 통과를 잡았다

A 는 처음에 통과했는데, 검사 문자열이 `doesNotContain("user_id = 2")` (공백 포함)이었다.
`normalized` 에는 공백이 없으므로 **이 검사는 무조건 통과한다.** 아무것도 검증하지 않았다.

공백을 뺀 `"user_id=2"` 로 고치자 A 도 실패로 바뀌었다. **실패가 늘어난 게 정직해진 것이다.**

### 8-7. 남은 판단 (미결)

**⑧ LLM 이 쓴 `user_id` 조건을 어떻게 할 것인가**

| 안 | 결과 |
|---|---|
| AND 로 묶는다 (현재) | `user_id=1 AND user_id=2` → 0건. 안전. 공격 시도가 0건으로 드러나 탐지에 유리 |
| 기존 조건을 지우고 주입 | 의도한 결과가 나온다. AST 순회 필요 |

**⑨ WHERE 없는 SQL 을 거부할 것인가**

| 안 | |
|---|---|
| 거부 | 전체 조회 자체를 막는 정책 |
| 주입 | 처리 가능하므로 내 데이터만 반환 |

정규식 버전은 **처리할 수 없어서** 거부했다. 능력의 한계지 정책이 아니었다.
파서 버전은 처리할 수 있다. **그럼 거부할 이유가 있는가.**

---

## 9. 실행 경로 재측정 — v0.1 vs v0.2 (2026-09-02)

8절은 **문자열 비교**였다. 여기서는 **실제 MySQL 에서 실행**해 결과 행으로 확인한다.

**방법**: `RawSqlDebugController` 에 `guarded` 플래그를 추가해 같은 엔드포인트로 둘 다 측정한다.

```bash
./scripts/run-attacks.sh query_ro false   # v0.1
./scripts/run-attacks.sh query_ro true    # v0.2
```

**환경**: MySQL 8.0.46 (Docker) / transactions 5,000건 / `query_ro` 계정 / 로그인 유저 = 1 (상수)

### 9-1. 결과

| # | 유형 | v0.1 | v0.2 | 막은 층 |
|---|---|---|---|---|
| 1 | DROP | 차단 | 차단 `GUARD_BLOCKED` | **1** (SELECT 아님) |
| 2 | DELETE | 차단 | 차단 `GUARD_BLOCKED` | **1** |
| 3 | UPDATE | 차단 | 차단 `GUARD_BLOCKED` | **1** |
| 4 | 다중 구문 | 차단 | 차단 `GUARD_BLOCKED` | **1** |
| 5 | 주석 우회 | **통과 200건** | 차단 `GUARD_BLOCKED` | **3** (user_id 사용) |
| 6 | UNION 인젝션 | 차단 | 차단 `SQL_ERROR` | 파싱/캐스팅 |
| 7 | 다른 user_id | **통과 200건** | 차단 `GUARD_BLOCKED` | **3** |
| 8 | 시스템 테이블 | **통과 89건** | 차단 `SQL_ERROR` | 부수 효과 (아래) |
| 9 | 서브쿼리 | 차단 | 차단 `DB_PERMISSION_DENIED` | **4b** |
| 10 | SLEEP(10) | 차단 | 차단 `SQL_ERROR` | 주입 후 문법 오류 |
| 11 | 대소문자 우회 | **통과 200건 (전체)** | 통과 **but user_id=1 로 한정** | **3 (주입)** |
| 12 | 주석 분할 | 차단 | 차단 `GUARD_BLOCKED` | 파싱 실패 |
| | **차단** | **8 / 12** | **11 / 12** | |

**오탐 (정상 질의 3종)**

| # | 질의 | v0.2 |
|---|---|---|
| N1 | `SELECT SUM(amount) ... WHERE category_id=1` | ✅ 통과 |
| N2 | `SELECT * ... ORDER BY amount DESC LIMIT 5` | ✅ 통과 |
| N3 | `SELECT category_id, SUM(amount) ... GROUP BY category_id` | ✅ 통과 |

**오탐 0 / 3.**

### 9-2. 11번은 "뚫린" 것이 아니다

`SeLeCt * FrOm transactions` 는 `user_id` 조건이 없으므로 **거부가 아니라 주입**된다.
결과가 200건으로 나오는 것은 `maxRows=200` 상한 때문이다. **행 수로는 주입 여부를 알 수 없다.**

직접 확인했다.

```bash
curl -X POST localhost:8080/api/debug/raw-sql \
  -d '{"sql":"SELECT COUNT(*) FROM transactions","account":"query_ro","guarded":true}'
→ {"rowCount":1,"rows":[{"COUNT(*)":1632}]}
```

**전체 5,000건 중 `user_id=1` 인 1,632건만 반환됐다.** 주입이 작동한다.

> **측정 방법의 한계**: `maxRows` 상한이 걸린 상태에서는 행 수만으로 방어 여부를 판정할 수 없다.
> 상한보다 작은 결과를 만드는 별도 질의(`COUNT(*)`)로 교차 확인해야 한다.

### 9-3. 차단 사유가 여러 층에 분산됐다

| 사유 | 건수 | 층 |
|---|---|---|
| `GUARD_BLOCKED` | 6 | 1 (SELECT only) · 3 (user_id) |
| `SQL_ERROR` | 3 | 파싱 실패 · 주입 후 문법 오류 |
| `DB_PERMISSION_DENIED` | 1 | 4b (MySQL GRANT) |

**한 층에만 의존하지 않는다는 것이 숫자로 확인된다.**
파서를 뚫어도 4b 가 남고, 4b 를 우회해도 1·3 이 남는다.

### 9-4. 의도하지 않은 방어 — 8번

`information_schema.tables` 조회에 `user_id = 1` 을 주입하자
**해당 테이블에 `user_id` 컬럼이 없어서** SQL 이 실패했다.

```
Unknown column 'user_id' in 'where clause'
```

**의도한 차단이 아니다.** 결과적으로 막혔을 뿐이다.

관찰된 성질: **`user_id` 컬럼이 없는 테이블은 주입 자체가 실패한다.**
허용 테이블(`transactions`)만 접근 가능해지는 부수 효과가 생긴다.

> ⚠️ 이것을 설계된 방어로 기록하면 안 된다. `user_id` 컬럼을 가진 다른 테이블이 생기면 이 성질은 사라진다.
> 테이블 화이트리스트(방어층 2)는 여전히 별도로 필요하다. `docs/06-retro.md` 남은 과제 참조.

### 9-5. 측정 설계 오류를 하나 더 잡았다

처음 v0.2 를 돌렸을 때 **정상 질의 N1~N3 가 전부 차단됐다.** 오탐 3/3 으로 보였다.

원인은 방어가 아니라 **측정 질의가 낡았기 때문**이다.

```sql
-- 8/31 작성 (방어가 없던 시점이라 user_id 를 직접 넣어야 했다)
SELECT SUM(amount) FROM transactions WHERE user_id=1 AND category_id=1

-- 9/2 정책 기준 (LLM 은 user_id 를 쓰지 않는다. 서버가 주입한다)
SELECT SUM(amount) FROM transactions WHERE category_id=1
```

새 정책에서 **LLM 이 `user_id` 를 쓰는 것 자체가 계약 위반**이므로, 저 질의는 애초에 발생하지 않는다.

`user_id` 를 빼고 다시 재자 **오탐 0/3** 이 나왔다.

> **정책이 바뀌면 측정 질의도 바뀌어야 한다.**
> 그대로 기록했으면 "우리 검증기는 정상 질의를 전부 막는다" 는 가짜 결론이 나왔을 것이다.

8/31 의 "파괴형 테스트를 먼저 실행해 결과가 오염된" 사례와 같은 종류의 실수다.
**측정 대상이 아니라 측정 도구를 의심해야 하는 경우가 두 번 나왔다.**
