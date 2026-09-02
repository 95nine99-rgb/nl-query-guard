# ADR-004 · 테스트는 DB에 어떻게 의존할 것인가

- 상태: **결정**
- 날짜: 2026-09-02
- 관련: `.github/workflows/ci.yml`

## 결정 (한 줄)

> **CI 에 MySQL 서비스 컨테이너를 띄운다.** Testcontainers 는 P2 에서 도입한다.

## 맥락

CI 가 **2026-08-24 부터 9일간 계속 실패**했다.

```
로컬:  ./gradlew build  → 통과
CI:    ci / build       → 실패
```

`NlQueryGuardApplicationTests.contextLoads()` 가 `@SpringBootTest` 로 컨텍스트를 통째로 띄우는데,
GitHub 러너에 MySQL 이 없어 `DialectFactoryImpl` 에서 실패했다.

**게이트 질문 ④ 의 답이 "CI 가 막아준다" 인데 빨간 CI 는 아무것도 막지 못한다.**
매번 실패하면 아무도 보지 않는다. 결정이 9일 미뤄진 동안 CI 는 사실상 없는 것과 같았다.

## 검토한 대안

| 대안 | 판단 | 장점 | 단점 |
|---|---|---|---|
| **A. CI 에 MySQL service 컨테이너** | **채택** | 실제 MySQL. 자바 코드를 안 건드림 | 로컬과 CI 설정이 이원화된다 |
| B. 테스트용 H2 (인메모리) | 버림 | 빠르다. 설정이 단순 | **MySQL 이 아니다.** 문법·타입 차이로 못 잡는 버그가 생긴다. `ddl-auto=validate` 와 `infra/schema.sql` 이 MySQL 전용이라 스키마를 이중 관리해야 한다 |
| C. Testcontainers | **보류 → P2** | **로컬과 CI 가 동일하게 동작.** "내 컴퓨터에선 되는데" 가 없다 | 학습 비용. `@DynamicPropertySource` 등 개념이 추가된다 |
| D. `contextLoads()` 제거, DB 없는 테스트만 | 버림 | 즉시 초록불. 10분 | **Spring 설정 오류를 못 잡는다.** 8/31 에 DataSource 자동설정 문제로 두 번 깨졌는데 D 로는 잡히지 않는다 |

## D 를 버린 이유가 실제 사건에서 나왔다

2026-08-31, `queryDataSource` 만 정의하자 Spring Boot 의 DataSource 자동 설정이 물러나
JPA 가 `query_ro` 로 스키마를 검증하다 실패했다. `JdbcTemplate` 에서 같은 실수를 반복했다.

**둘 다 컴파일은 통과했고 단위 테스트도 통과했을 것이다.** 기동해야만 드러난다.
`contextLoads()` 를 없애면 이 계열의 오류를 CI 가 영영 못 잡는다.

## C 를 P2 로 미룬 이유

P2(`p2-deploy-observe`)가 Docker·CI/CD 프로젝트다. Testcontainers 는 거기서 다루는 것이
맥락상 자연스럽고, 지금 도입하면 P1 마감 안에 끝나지 않는다.

**"더 나은 선택지를 알지만 시간 때문에 A 로 갔다"** 를 기록으로 남긴다. 몰라서 안 한 것이 아니다.

## 채택한 구성

```yaml
services:
  mysql:
    image: mysql:8.0
    env:
      MYSQL_ROOT_PASSWORD: rootpw
      MYSQL_DATABASE: nlquery
    options: --health-cmd="mysqladmin ping ..." --health-interval=10s
```

단계는 4개다.

1. **스키마 적용** — `ddl-auto=validate` 라 스키마가 먼저 있어야 한다 (ADR-003)
2. **계정 생성** — `app`(전체) / `query_ro`(읽기 전용). 로컬 구성과 동일하게
3. **읽기전용 권한 검증** — 아래 참조
4. `./gradlew build`

## 3번을 넣은 이유 — 코드가 아니라 인프라를 검사한다

```bash
if mysql -uquery_ro -propw nlquery -e "SELECT 1 FROM users LIMIT 1;"; then
  echo "❌ query_ro 가 users 를 읽을 수 있다. 방어층 4b 가 깨졌다."
  exit 1
fi
```

**2026-08-27 에 `grant-readonly.sql` 을 실행하지 않아 `query_ro` 에 `GRANT USAGE` 만 있었다.**
그 상태로 5일이 지났고 아무도 몰랐다. 8/31 측정에서 12종 중 8종이 막힌 것은 전적으로 이 계정 설정 덕분인데,
**설정 하나가 방어의 3분의 2를 좌우하는데 아무도 감시하지 않았다.**

반대 방향도 위험하다. 누가 `GRANT SELECT ON nlquery.*` 로 바꾸면 `users` 가 열린다.
**모든 테스트는 그대로 통과한다.** 애플리케이션 코드가 안 바뀌었으니까.

→ CI 가 **권한 자체를 검사**하게 했다. 코드 테스트로는 잡을 수 없는 계층이다.

## 되돌릴 조건

- CI 실행 시간이 문제가 되면 → 단위 테스트 잡과 통합 테스트 잡을 분리한다
- P2 에서 Testcontainers 를 익히면 → C 로 전환하고 이 ADR 을 supersede 한다

## 관련

- `docs/05-result.md` 6·9절 — `query_ro` 권한이 차단율에 미치는 영향
- `docs/01-adr/ADR-002-validation-strategy.md` — 방어층 4b
- `docs/01-adr/ADR-003-schema-ownership.md` — `ddl-auto=validate`
