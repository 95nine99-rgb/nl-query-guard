# API 정의서

구현 **전에** 초안을 쓰고, 구현 **후에** 실제 응답과 대조한다.

## POST /api/queries — 자연어 질의

### 요청
```json
{ "text": "이번 달 카페에서 얼마 썼어?" }
```

### 응답 200 — 실행됨
```json
{
  "status": "EXECUTED",
  "intent": "SUM",
  "confidence": 0.93,
  "resolver": "RULE",
  "result": { "total": 84000, "currency": "KRW" },
  "meta": { "latencyMs": 0, "tokenCost": 0 }
}
```

### 응답 200 — 되물음 (신뢰도 미달)
```json
{
  "status": "NEEDS_CLARIFICATION",
  "confidence": 0.41,
  "question": "어느 기간을 말씀하시는 걸까요?"
}
```

### 응답 400 — 차단됨
```json
{
  "status": "BLOCKED",
  "reason": "NON_SELECT_STATEMENT",
  "detail": "허용되지 않은 구문입니다"
}
```

## 에러 코드

| code | 언제 | HTTP |
|---|---|---|
| `NON_SELECT_STATEMENT` | SELECT 외 구문 | 400 |
| `TABLE_NOT_ALLOWED` | 화이트리스트 밖 테이블 | 400 |
| `COLUMN_NOT_ALLOWED` | 화이트리스트 밖 컬럼 | 400 |
| `MULTIPLE_STATEMENTS` | 세미콜론 다중 구문 | 400 |
| `DENY_KEYWORD` | DROP/DELETE/UPDATE 등 | 400 |
| `LLM_UNAVAILABLE` | 재시도 소진 | 503 |
| `QUERY_TIMEOUT` | 실행 타임아웃 | 504 |

## GET /api/queries/{id} — 질의 로그 단건

## GET /actuator/health, /actuator/metrics

> 구현 후 실제 응답과 대조 완료: [ ]
