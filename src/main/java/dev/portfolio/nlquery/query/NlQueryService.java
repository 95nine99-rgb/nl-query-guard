package dev.portfolio.nlquery.query;

import dev.portfolio.nlquery.common.error.ErrorCode;
import dev.portfolio.nlquery.guard.SqlGuardException;
import dev.portfolio.nlquery.guard.UserIdEnforcer;
import dev.portfolio.nlquery.llm.LlmClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 자연어 질의 파이프라인.
 *
 *   질문 → 프롬프트 조립 → LLM → 검증·user_id 주입 → 읽기전용 실행 → 결과
 *
 * 부품(LlmClient, UserIdEnforcer, queryJdbcTemplate)은 각각 독립적으로 완성돼 있고
 * 이 클래스는 순서대로 부르는 역할만 한다.
 */
@Service
public class NlQueryService {

    private static final String PROMPT_PATH = "prompts/sql-select.v1.txt";

    private final LlmClient llmClient;
    private final UserIdEnforcer enforcer;
    private final JdbcTemplate queryJdbc;
    private final int maxRegenerate;

    public NlQueryService(LlmClient llmClient,
                          UserIdEnforcer enforcer,
                          // ⚠️ 이 한 줄이 방어층 4 전체를 결정한다.
                          //    빠지면 @Primary 인 appJdbcTemplate(전체 권한)이 주입된다.
                          @Qualifier("queryJdbcTemplate") JdbcTemplate queryJdbc,
                          @Value("${app.guard.max-regenerate}") int maxRegenerate) {
        this.llmClient = llmClient;
        this.enforcer = enforcer;
        this.queryJdbc = queryJdbc;
        this.maxRegenerate = maxRegenerate;
    }

    public NlQueryResult ask(String question, long loginUserId) throws IOException {
        String template = Files.readString(Path.of(PROMPT_PATH));

        String rawSql = null;
        ErrorCode lastError = null;

        // 0회차 = 첫 시도. 이후 maxRegenerate 회까지 재생성한다.
        for (int attempt = 0; attempt <= maxRegenerate; attempt++) {

            String prompt = buildPrompt(template, question, lastError);
            rawSql = sanitize(llmClient.generate(prompt));

            try {
                String executedSql = enforcer.enforce(rawSql, loginUserId);
                List<Map<String, Object>> rows = queryJdbc.queryForList(executedSql);
                return NlQueryResult.ok(rawSql, executedSql, attempt + 1, rows);

            } catch (SqlGuardException e) {
                // 검증에서 막혔다. 이유를 다음 프롬프트에 알려주고 다시 시킨다.
                lastError = e.getErrorCode();

            } catch (DataAccessException e) {
                // ⚠️ 판단: DB 가 막은 것은 재생성하지 않는다.
                //    권한 밖 테이블을 물어본 질문은 다시 시켜도 같은 결과가 나온다.
                //    재시도 비용만 늘고 차단 사유도 흐려진다.
                return NlQueryResult.blocked(classify(e), rawSql, attempt + 1);
            }
        }

        // maxRegenerate + 1 회 전부 실패
        return NlQueryResult.blocked(lastError, rawSql, maxRegenerate + 1);
    }

    /**
     * 프롬프트를 조립한다. 직전 시도가 실패했으면 그 이유를 덧붙인다.
     *
     * 판단 ⑧ — 거부는 사용자 응답이 아니라 재생성 신호다.
     * 여기서 그 신호가 프롬프트로 바뀐다.
     */
    private String buildPrompt(String template, String question, ErrorCode lastError) {
        String prompt = template
                .replace("{{TODAY}}", LocalDate.now().toString())
                .replace("{{QUERY}}", question);

        if (lastError == null) {
            return prompt;
        }
        return prompt + "\n\n[직전 시도가 거부되었다]\n" + retryHint(lastError) + "\n다시 만들어라.\nSQL:";
    }

    private String retryHint(ErrorCode code) {
        return switch (code) {
            case LLM_WROTE_USER_ID ->
                    "직전 응답에 user_id 조건이 있었다. user_id 를 절대 쓰지 마라. 서버가 주입한다.";
            case NOT_SELECT ->
                    "직전 응답이 SELECT 문이 아니었다. 데이터를 바꾸는 구문은 만들 수 없다.";
            case UNPARSEABLE_SQL ->
                    "직전 응답이 올바른 SQL 이 아니었다. 설명·접두사·코드블록 없이 SELECT 문 하나만 출력하라.";
            default -> "직전 응답이 거부되었다. 규칙을 다시 확인하라.";
        };
    }

    /**
     * LLM 출력에서 SQL 이 아닌 껍데기를 벗긴다.
     *
     * 프롬프트 규칙 5에 "SQL 만 출력한다" 고 적었으나 실제로는
     * "SQL: " 접두사와 마크다운 코드블록이 붙었다.
     * 프롬프트 마지막 줄이 "SQL:" 이라 모델이 형식을 따라한 것으로 보인다.
     *
     * ⚠️ 세미콜론은 제거하지 않는다.
     *    "SELECT ...; DROP TABLE users" 를 앞부분만 남기고 통과시키게 된다.
     *    다중 구문 판단은 JSQLParser 의 몫이다.
     */
    private String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim()
                .replaceAll("```sql", "")
                .replaceAll("```", "")
                .trim();

        if (s.toUpperCase().startsWith("SQL:")) {
            s = s.substring(4).trim();
        }
        return s;
    }

    /** DB 예외를 에러 코드로 옮긴다. 원인 메시지로 판단한다. */
    private ErrorCode classify(DataAccessException e) {
        String msg = e.getMostSpecificCause().getMessage();
        if (msg == null) {
            return ErrorCode.DB_PERMISSION_DENIED;
        }
        String m = msg.toLowerCase();
        if (m.contains("denied")) {
            return ErrorCode.DB_PERMISSION_DENIED;
        }
        if (m.contains("timeout") || m.contains("timed out")) {
            return ErrorCode.QUERY_TIMEOUT;
        }
        return ErrorCode.DB_PERMISSION_DENIED;
    }
}
