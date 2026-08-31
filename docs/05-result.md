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
