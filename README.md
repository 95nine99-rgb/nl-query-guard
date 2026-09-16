# 거래내역 자연어 검색 백엔드 — Text2SQL 검증 및 안전 실행

> **LLM은 자연어를 SQL로 그럴듯하게 바꾼다. 그런데 그 SQL이 DROP인지, 남의 데이터를 훑는지는 모델이 판단할 수 없다.**
> 무엇을 실행시킬지 정하는 판단 레이어를 만든 프로젝트다.

## 3줄 요약

| | |
|---|---|
| **문제** | LLM이 생성한 SQL을 그대로 실행하면 데이터가 오염되거나 남의 데이터가 새어나간다 |
| **대안** | 정규식 검증 / LLM 재검증 / **파서 + user_id 서버 주입 + 실행계층 방어** (→ [ADR-002](docs/01-adr/ADR-002-validation-strategy.md)) |
| **결과** | 악의적 SQL 12종 차단 **8/12 → 11/12**, 정상 질의 오탐 **0/3** |

## 핵심 수치

| 지표 | before | after | 측정 환경 |
|---|---|---|---|
| 악의적 SQL 12종 차단 | **8 / 12** | **11 / 12** | MySQL 8.0.46(Docker) / transactions 5,000건 / `query_ro` 계정 / 2026-09-02 |
| 정상 질의 오탐 | 0 / 3 | **0 / 3** | 위와 동일 |
| 대소문자 우회 질의 결과 행 | 5,000건 전체 | **1,632건** (user_id=1만) | 위와 동일. `COUNT(*)` 교차 확인 |
| 복합 인덱스 — 읽은 행 | 995 | **20** | `EXPLAIN ANALYZE` / 2026-08-31 |
| 복합 인덱스 — 정렬 | `Using filesort` | **제거됨** (reverse 스캔) | 위와 동일 |
| CI | 실패 (9일간) | **통과** | GitHub Actions + MySQL 8.0 service |

**차단 사유가 한 층에 몰려 있지 않다.** `GUARD_BLOCKED` 6 / `SQL_ERROR` 3 / `DB_PERMISSION_DENIED` 1.
파서를 뚫어도 DB 권한이 남고, DB 권한 안쪽의 정상 SELECT는 `user_id` 주입이 막는다.

전체는 [docs/05-result.md](docs/05-result.md).

---

## 실제로 동작하는 화면

**정상 질의**

```bash
curl -X POST localhost:8080/api/nl-query \
  -H 'Content-Type: application/json' \
  -d '{"question":"8월 카테고리별 지출 합계"}'
```

```json
{
  "status": "OK",
  "rawSql":      "SELECT c.name, SUM(t.amount) FROM transactions t JOIN categories c ON t.category_id = c.id WHERE t.transaction_at >= '2026-08-01' AND t.transaction_at < '2026-09-01' GROUP BY c.name",
  "executedSql": "SELECT c.name, SUM(t.amount) FROM transactions t JOIN categories c ON t.category_id = c.id WHERE user_id = 1 AND t.transaction_at >= '2026-08-01' AND t.transaction_at < '2026-09-01' GROUP BY c.name",
  "attempts": 1,
  "rows": [{"name":"교통","SUM(t.amount)":22891472}, {"name":"쇼핑","SUM(t.amount)":25709627}, ...]
}
```

> `rawSql` 은 LLM 이 만든 것, `executedSql` 은 서버가 `user_id = 1` 을 주입한 것이다.
> **LLM 은 user_id 를 쓰지 않는다. 서버만 쓴다.**

**차단되는 화면 — 같은 엔드포인트, 서로 다른 층이 막는다**

| 질문 | `errorCode` | 막은 층 |
|---|---|---|
| 거래내역 전부 삭제해줘 | `NOT_SELECT` | **1 · 구문** — `DELETE` 는 `instanceof Select` 에서 거부 |
| users 테이블 전부 보여줘 | `DB_PERMISSION_DENIED` | **4b · DB 권한** — 파서는 통과했다. MySQL 이 막았다 |
| 2번 사용자의 거래내역 보여줘 | `LLM_WROTE_USER_ID` | **3 · 권한 경계** — LLM 이 계약을 어겼다 |
| 사용자 이메일 목록 보여줘 | `UNPARSEABLE_SQL` | 파싱 실패 — 모델이 SQL 을 만들지 못했다 |

사용자에게는 `"해당 조회는 이용할 수 없습니다"` 만 나간다. **왜 막혔는지 알려주면 우회 방법을 알려주는 셈이다.**

---

## 실행법

```bash
git config core.hooksPath .githooks   # 비밀 커밋 차단 훅 (리포당 1회)

docker compose up -d --wait           # MySQL 8.0
docker exec -i nlq-mysql mysql -uroot -prootpw nlquery < infra/schema.sql
docker exec -i nlq-mysql mysql -uroot -prootpw nlquery < infra/grant-readonly.sql

ollama serve                          # 별도 터미널
ollama pull qwen2.5:3b

./gradlew bootRun
```

시드 데이터는 `--spring.profiles.active=seed` 로 5,000건을 생성한다. **2026-08 한 달치**다.

---

## 이 프로젝트를 만든 방식 — 먼저 깨뜨리고, 그다음에 막는다

**1. 방어를 만들기 전에 직접 뚫어봤다.**
`DROP TABLE transactions` 를 실행해 5,000건을 날렸다. `SELECT * FROM users` 로 이메일을 조회했다.
"이론상 위험하다" 와 "방금 내가 날렸다" 는 다르다.

**2. 뚫린 뒤에 무엇을 막아야 하는지가 정의됐다.**
악의적 SQL 12종을 실행해보니 **읽기 전용 계정 하나로 8종이 막혔다.** 파서는 한 줄도 짜지 않았다.
뚫린 4종은 전부 `transactions` 를 `SELECT` 하는 **정상 요청**이었다.
**DB 는 "무엇을" 만 안다. "누구 것인지" 는 앱만 안다.** 여기서 `user_id` 주입이 필요한 이유가 정의됐다.

**3. 정규식으로 먼저 만들고, 깨지는 것을 확인한 뒤 파서로 옮겼다.**
같은 테스트 7개로 두 구현을 비교했다. 통과 수는 **4:4 로 같았지만 실패의 성격이 달랐다.**
정규식은 문자열 리터럴 안의 `--` 를 잘라 **SQL 자체를 부쉈고**, 소문자 `where` 를 못 잡아 **정상 요청을 거부**했다.
파서의 실패는 `user_id=1 AND user_id=2` → **0건 반환**이었다. 데이터가 새지 않는다.
→ [ADR-002](docs/01-adr/ADR-002-validation-strategy.md)

**4. LLM 을 붙이자 내가 짠 공격 목록의 한계가 드러났다.**
모델이 자연스럽게 만든 `category_id IN (SELECT ...)` 이 `user_id` 탐지를 우회했다.
직접 짠 악의적 SQL 12종에는 `IN` 과 `BETWEEN` 이 없었다. **책상에서 만든 목록보다 실제 사용이 넓었다.**
→ [07-ai-usage.md](docs/07-ai-usage.md)

**5. 측정 도구를 세 번 의심해야 했다.**
파괴형 테스트를 먼저 돌려 결과가 오염됐고, 낡은 기준의 질의로 오탐률을 잘못 쟀고,
프롬프트 예시에 있는 질의로 모델 성능을 측정할 뻔했다. 전부 [07-ai-usage.md](docs/07-ai-usage.md) 에 기록했다.

---

## 방어 계층

| 층 | 무엇 | 어디서 | 상태 |
|---|---|---|---|
| **1** | `SELECT` 외 구문 · 다중 구문 거부 | 앱 (JSQLParser) | ✅ |
| **2** | 허용 밖 테이블·컬럼 화이트리스트 | 앱 (파서) | ❌ **미구현** |
| **3** | `user_id` 서버 강제 주입 | 앱 | ✅ |
| **4a** | 변경 구문 차단 | 앱 (JDBC `readOnly`) | ✅ |
| **4b** | **권한 밖 접근 차단** | **DB (MySQL GRANT)** | ✅ |
| **4c** | 결과 200행 · 3초 제한 | 앱 (`maxRows`, `queryTimeout`) | ✅ |

**1·2·3·4a·4c 는 내가 짠 코드다. 틀릴 수 있다. 4b 만 내 코드 밖에 있다.**

실제로 2026-08-31 에 DataSource 자동 설정을 잘못 건드려 기동이 두 번 깨졌다.
그래서 CI 가 **코드가 아니라 권한 자체를 검사**한다. `query_ro` 가 `users` 를 읽을 수 있으면 빌드를 실패시킨다.
→ [ADR-004](docs/01-adr/ADR-004-test-db-dependency.md)

> ⚠️ **2층이 없다.** `information_schema` 조회가 막힌 것은 그 테이블에 `user_id` 컬럼이 없어
> 주입된 SQL 이 깨졌기 때문이다. **의도한 방어가 아니라 우연이다.** [06-retro.md](docs/06-retro.md) 남은 과제.

---

## 아직 안 한 것

숨기지 않고 적는다. 몰라서 안 한 것과 알고 미룬 것은 다르다.

| | 이유 |
|---|---|
| 테이블 화이트리스트 (방어층 2) | 시간. `TablesNamesFinder` 로 30분이면 되나 착수하지 못했다 |
| 의도 분류 · 신뢰도 임계값 | Ollama 는 신뢰도 점수를 주지 않는다. 재생성 횟수로 대체 검토 |
| 프롬프트 회귀 CI | 정답셋이 없어 무조건 실패한다. **빨간 CI 는 아무것도 막지 못하므로** 수동 실행으로 꺼뒀다 |
| Testcontainers | 로컬·CI 동일 동작이라 더 낫다. 학습 비용 때문에 P2 로 미뤘다 |
| Spring AI | 추상화를 쓰기 전에 `LlmClient` 포트를 직접 만들어보는 쪽을 택했다 |
| `user_id` 조건 제거 방식 | 현재는 거부 + 재생성. 제거 후 주입이 오탐을 더 줄일 수 있다 |

---

## 기술 선택

| | 선택 | 왜 |
|---|---|---|
| SQL 검증 | **JSQLParser** | 정규식은 문자열 리터럴·주석·대소문자를 구분하지 못한다. 실측으로 확인 |
| LLM | **Ollama + qwen2.5:3b** (로컬) | 비용 0, API 키 노출 없음. **성능이 낮은 것이 오히려 재료다.** 완벽한 모델을 쓰면 검증층의 존재 이유가 보이지 않는다 |
| 스키마 | **SQL 스크립트 + `ddl-auto=validate`** | `update` 는 인덱스를 지우지 못해 실험 통제가 깨진다 → [ADR-003](docs/01-adr/ADR-003-schema-ownership.md) |
| 테스트 DB | **CI 에 MySQL service** | H2 는 MySQL 이 아니다. 문법 차이로 못 잡는 버그가 생긴다 → [ADR-004](docs/01-adr/ADR-004-test-db-dependency.md) |

---

## 문서

| 문서 | 내용 |
|---|---|
| [00-PRD](docs/00-PRD.md) | 무엇을 왜 만드는가, 비목표, 성공 기준 |
| [01-adr](docs/01-adr/) | **검토한 대안과 버린 이유** (ADR-001~004) |
| [02-api](docs/02-api.md) | API 정의서 |
| [03-erd](docs/03-erd.md) | 데이터 모델 + 인덱스 근거 |
| [04-experiment](docs/04-experiment.md) | 측정 설계 — **측정 전에 쓴다** |
| [05-result](docs/05-result.md) | 측정 결과 + 측정의 한계 |
| [06-retro](docs/06-retro.md) | 회고 + 남은 과제 |
| [07-ai-usage](docs/07-ai-usage.md) | **AI 가 그럴듯하게 틀린 사례 13건** |
| [08-structure](docs/08-structure.md) | 패키지 구조 |
| [09-guardrails](docs/09-guardrails.md) | 가드레일 — 사람이 아니라 시스템이 막는다 |
| [eval-cases](docs/eval-cases.md) | 정답셋 (미완성 — 남은 과제) |
| [logs/](logs/) | 날짜별 작업 기록과 그날의 판단 |

---

## 작업 기간

**실작업 7일.** 2026-08-21 ~ 09-16 사이, 가용 시간이 확보된 날에만 진행했다.

처음에 "하루 6~8시간 매일" 로 계획했으나 실제 가용 시간은 주 2~3일이었다.
**네 번 일정을 재조정했고, 범위를 자르지 않고 날짜를 미는 쪽을 택했다.**
계획과 실제의 차이, 그리고 그때마다의 판단은 [SCHEDULE.md](../SCHEDULE.md) 와 [logs/](logs/) 에 남아 있다.

---

## 출처

이 프로젝트는 카카오페이 기술블로그의 두 사례에서 출발했다. **2026-09-16 에 직접 접속해 확인한 URL 이다.**

### 1. [거래내역에 감성과 지능을 더하다: 이미지 캘린더와 자연어 검색으로 만드는 특별한 금융 경험](https://tech.kakaopay.com/post/transaction-summary-and-search-with-ai/)
2025. 5. 15 · 제2회 카카오페이 해커톤 2등 (모즈 팀)

| | |
|---|---|
| **원문** | AWS Nova Canvas + **Claude 3.5 기반 Text2SQL 및 tool calling**. 자연어 검색을 `Text2SQL → Tool Selection → Tool Execution` 3단계로 구성 |
| **이 프로젝트** | 상용 API 대신 **로컬 LLM(Ollama)**. Tool Selection 대신 **검증 레이어**를 뒀다. 파서 기반 구문·구조 검증, `user_id` 서버 강제 주입, 읽기 전용 DB 계정 + row limit + 타임아웃 |

### 2. [Document AI로 문서 검토 한방에 끝내기](https://tech.kakaopay.com/post/ifkakao2024-document-ai)
2024. 12. 19 · 카카오페이손해보험 · if(kakaoAI)2024 발표

| | |
|---|---|
| **원문** | `OCR → Layout Analysis → Classification → Parsing` 4단계로 문서를 인식. 일 평균 3,000장을 사람 대신 처리 |
| **이 프로젝트** | **AI 출력을 그대로 믿지 않고 뒤에 검증 단계를 둔다**는 구조를 가져왔다. 다만 우리 도메인에서는 모델이 confidence 를 제공하지 않아(Ollama) 임계값 대신 **거부 + 재생성** 으로 구현했다 |

### 원문과 갈라진 지점

원문(1번)은 LLM 이 생성한 SQL 을 **tool calling 으로 이어서 처리**한다. 검증 자체를 모델 체계 안에서 푼다.

이 프로젝트는 거기서 갈라졌다 — **모델이 만든 것을 모델로 검증하면 그 검증은 왜 믿는가.**
그래서 검증을 **파서와 DB 권한이라는 결정론적 층**으로 옮겼다.
JSQLParser 는 같은 입력에 항상 같은 판정을 내리고, MySQL `GRANT` 는 앱 코드가 어떻게 바뀌든 강제된다.

> ⚠️ **2026-09-16 정정**: 이 README 는 원래 1번 글을 "의도 분류 → Entity 추출 → SQL 생성 → SQL 검증 4단계" 로 적고 있었다.
> 실제 확인 결과 원문의 단계는 `Text2SQL → Tool Selection → Tool Execution` 이었다.
> **읽은 지 오래된 글을 기억으로 요약해 적은 것이 원인이다.** 확인 후 수정했다.
