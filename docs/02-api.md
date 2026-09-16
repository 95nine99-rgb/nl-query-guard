# API 정의서

구현 **전에** 초안을 쓰고, 구현 **후에** 실제 응답과 대조한다.

> **대조 완료: 2026-09-16.** 초안과 달라진 부분은 맨 아래 "초안과 달라진 점" 참조.

---

## POST /api/nl-query — 자연어 질의

### 요청

```json
{ "question": "8월 카테고리별 지출 합계" }
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `question` | string | 자연어 질의 |

> 로그인 사용자 ID 는 요청에 넣지 않는다. **서버가 아는 값과 클라이언트가 보낸 값을 섞지 않는다.**
> 현재는 인증 미구현으로 `NlQueryController` 에 상수(`1L`)로 고정돼 있다.

### 응답 200 — 실행됨

```json
{
  "status": "OK",
  "message": null,
  "errorCode": null,
  "rawSql": "SELECT c.name, SUM(t.amount) FROM transactions t JOIN categories c ON t.category_id = c.id WHERE t.transaction_at >= '2026-08-01' AND t.transaction_at < '2026-09-01' GROUP BY c.name",
  "executedSql": "SELECT c.name, SUM(t.amount) FROM transactions t JOIN categories c ON t.category_id = c.id WHERE user_id = 1 AND t.transaction_at >= '2026-08-01' AND t.transaction_at < '2026-09-01' GROUP BY c.name",
  "attempts": 1,
  "rows": [
    { "name": "교통", "SUM(t.amount)": 22891472 },
    { "name": "쇼핑", "SUM(t.amount)": 25709627 }
  ]
}
```

| 필드 | 설명 |
|---|---|
| `status` | `OK` \| `BLOCKED` |
| `message` | 사용자에게 보여줄 문구. 성공이면 `null` |
| `errorCode` | 시스템용. 차단 사유 집계에 쓴다. 성공이면 `null` |
| **`rawSql`** | **LLM 이 만든 원본** |
| **`executedSql`** | **검증·`user_id` 주입 후 실제 실행된 SQL** |
| `attempts` | 몇 번째 시도에 성공/실패했는가 (1~3) |
| `rows` | 결과. 컬럼이 질의마다 달라 `Map` 형태다 |

> ⚠️ `rawSql` · `executedSql` 노출은 **데모 목적**이다.
> 운영이라면 내부 스키마가 새어 나가므로 `errorCode` 만 남긴다. `docs/06-retro.md` 참조.

### 응답 200 — 차단됨

**차단도 200 이다.** HTTP 오류가 아니라 정상적인 처리 결과이기 때문이다.

```json
{
  "status": "BLOCKED",
  "message": "해당 조회는 이용할 수 없습니다",
  "errorCode": "LLM_WROTE_USER_ID",
  "rawSql": "SQL: SELECT * FROM transactions WHERE user_id = '2'",
  "executedSql": null,
  "attempts": 3,
  "rows": []
}
```

`message` 는 사유를 구분하지 않는다. **왜 막혔는지 알려주면 우회 방법을 알려주는 셈이다.**

---

## 에러 코드

| code | 언제 | 막은 층 | 사용자 문구 |
|---|---|---|---|
| `NOT_SELECT` | `SELECT` 외 구문 | 1 (파서) | 해당 조회는 이용할 수 없습니다 |
| `LLM_WROTE_USER_ID` | LLM 이 `user_id` 조건을 생성 | 3 (권한 경계) | 해당 조회는 이용할 수 없습니다 |
| `UNPARSEABLE_SQL` | 파싱 불가 | 1 (파서) | 질문을 다시 입력해주세요 |
| `DB_PERMISSION_DENIED` | 권한 밖 테이블 접근 | **4b (MySQL GRANT)** | 해당 조회는 이용할 수 없습니다 |
| `QUERY_TIMEOUT` | 3초 초과 | 4c | 조회 시간이 초과되었습니다 |

정의: `common/error/ErrorCode.java`

---

## 재생성

`SqlGuardException` 이 발생하면 **사용자에게 에러를 보내지 않고 LLM 에 다시 요청**한다.
프롬프트 뒤에 실패 사유별 경고를 덧붙인다.

```
[직전 시도가 거부되었다]
직전 응답에 user_id 조건이 있었다. user_id 를 절대 쓰지 마라. 서버가 주입한다.
다시 만들어라.
```

- 최대 `app.guard.max-regenerate` 회 (기본 2). 첫 시도 포함 총 3회
- **`DataAccessException`(DB 가 막은 경우)은 재생성하지 않는다.**
  권한 밖 테이블을 물어본 질문은 다시 시켜도 같은 결과가 나온다

---

## GET /api/transactions — 거래내역 조회 (일반)

자연어를 거치지 않는 일반 조회 API. 필터·페이징·정렬을 지원한다.
`TransactionController` 참조.

## GET /actuator/health, /actuator/metrics

---

## 초안과 달라진 점 (2026-08-22 초안 → 2026-09-16 실제)

| 항목 | 초안 | 실제 | 왜 |
|---|---|---|---|
| 경로 | `POST /api/queries` | `POST /api/nl-query` | 일반 조회 API(`/api/transactions`)와 역할이 다름을 이름에서 드러냄 |
| 요청 필드 | `text` | `question` | 자연어 질문임을 명확히 |
| `intent` · `confidence` · `resolver` | 있음 | **없음** | 의도 분류를 구현하지 않았다. Ollama 는 confidence 를 제공하지 않는다 |
| `NEEDS_CLARIFICATION` | 있음 | **없음** | 신뢰도 임계값이 없으므로 되묻기 조건을 만들 수 없다. **재생성으로 대체** |
| 차단 시 HTTP | 400 | **200** | 차단은 오류가 아니라 정상 처리 결과다 |
| 에러 코드 | 7종 | **5종** | `TABLE_NOT_ALLOWED` · `COLUMN_NOT_ALLOWED` 는 방어층 2 미구현으로 없음 |
| `rawSql` · `executedSql` | 없음 | **있음** | "LLM 이 뭘 만들었고 서버가 뭘로 바꿨나" 가 이 프로젝트의 핵심이라 응답에서 보여야 한다 |
| `GET /api/queries/{id}` | 있음 | **없음** | 질의 로그 테이블을 만들었으나 조회 API 는 구현하지 않았다 |

**초안을 지우지 않고 남긴다.** 계획과 실제가 달라진 지점 자체가 기록이다.
