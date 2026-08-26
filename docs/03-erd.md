# 데이터 모델

핵심은 **NL_QUERY_LOG**다. 어떤 질의가 어떤 SQL이 됐고 무엇 때문에 통과 또는 차단됐는지가
전부 남아야 나중에 차단율과 정확도를 계산할 수 있다. 로그를 나중에 붙이면 재현이 안 된다.

```mermaid
erDiagram
  USERS ||--o{ TRANSACTIONS : "보유"
  CATEGORIES ||--o{ TRANSACTIONS : "분류"
  USERS ||--o{ NL_QUERY_LOG : "질의"
  PROMPT_VERSION ||--o{ NL_QUERY_LOG : "사용된 프롬프트"
  GUARD_RULE ||--o{ NL_QUERY_LOG : "검증 근거"
  PROMPT_VERSION ||--o{ EVAL_RUN : "회귀 대상"
  EVAL_CASE ||--o{ EVAL_RESULT : "케이스"
  EVAL_RUN ||--|{ EVAL_RESULT : "결과"
  USERS {
    bigint id PK
    varchar email
  }
  CATEGORIES {
    bigint id PK
    varchar name "카페 식비 교통 등"
  }
  TRANSACTIONS {
    bigint id PK
    bigint user_id FK "권한 경계 - 반드시 강제 주입"
    bigint category_id FK
    varchar title
    int amount
    datetime transaction_at "복합 인덱스 대상"
  }
  NL_QUERY_LOG {
    bigint id PK
    bigint user_id FK
    text raw_text "원 질의"
    varchar intent "5종"
    decimal intent_confidence
    varchar resolver "RULE 또는 LLM"
    text generated_sql
    varchar validation "PASS BLOCKED REGENERATED"
    varchar block_reason
    boolean executed
    int latency_ms
    int token_cost
    bigint prompt_version_id FK
  }
  GUARD_RULE {
    bigint id PK
    varchar rule_type "ALLOWED_TABLE ALLOWED_COLUMN DENY_KEYWORD"
    varchar value
    boolean enabled
  }
  PROMPT_VERSION {
    bigint id PK
    varchar name
    text body
    varchar git_sha "prompts 디렉터리 커밋"
    datetime created_at
  }
  EVAL_CASE {
    bigint id PK
    text raw_text "정답셋 30건"
    varchar expected_intent
    varchar expected_sql_shape
    boolean malicious "악의적 12종 여부"
  }
  EVAL_RUN {
    bigint id PK
    bigint prompt_version_id FK
    datetime run_at
    decimal intent_accuracy
    decimal block_rate
    varchar ci_run_url
  }
  EVAL_RESULT {
    bigint id PK
    bigint eval_run_id FK
    bigint eval_case_id FK
    boolean passed
    text actual_sql
  }
```

## 인덱스 근거

| 인덱스 | 대상 쿼리 | 근거 |
|---|---|---|
| `idx_tx_user_cat_time (user_id, category_id, transaction_at)` | 카테고리별 기간 합계 | 등가조건 → 등가조건 → 범위·정렬 순서 |
| `idx_log_created (created_at)` | 질의 로그 조회 | 미확인 — 데이터 늘려보고 판단 |
