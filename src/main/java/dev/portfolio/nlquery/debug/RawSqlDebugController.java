package dev.portfolio.nlquery.debug;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ⚠️ 실험 전용. 임의 SQL 을 검증 없이 실행한다.
 *
 * 목적: "방어가 없으면 무슨 일이 일어나는가" 를 직접 재현한다.
 *      막는 코드를 짜기 전에 뚫려보는 것이 이 프로젝트의 원칙이다.
 *
 * 실행:
 *   ./gradlew bootRun --args='--spring.profiles.active=unguarded'
 *
 * @Profile("unguarded") 가 없으면 이 빈은 등록되지 않는다. 평소 기동에는 존재하지 않는다.
 * 오후에 여기에 SqlValidator 를 끼워 before/after 차단율을 측정한다.
 */
@Profile("unguarded")
@RestController
@RequestMapping("/api/debug")
public class RawSqlDebugController {

    private final JdbcTemplate appJdbc;      // app 계정 — 전체 권한
    private final JdbcTemplate readOnlyJdbc; // query_ro — ADR-002 방어층 4

    public RawSqlDebugController(@Qualifier("appJdbcTemplate") JdbcTemplate appJdbc,
                                 @Qualifier("queryJdbcTemplate") JdbcTemplate readOnlyJdbc) {
        this.appJdbc = appJdbc;
        this.readOnlyJdbc = readOnlyJdbc;
    }

    public record RawSqlRequest(String sql, String account) {}

    public record RawSqlResult(
            String account, boolean success, String errorCode,
            String message, Integer rowCount, List<Map<String, Object>> rows) {}

    @PostMapping("/raw-sql")
    public RawSqlResult run(@RequestBody RawSqlRequest request) {
        boolean useReadOnly = "query_ro".equalsIgnoreCase(request.account());
        JdbcTemplate jdbc = useReadOnly ? readOnlyJdbc : appJdbc;
        String account = useReadOnly ? "query_ro" : "app";
        String sql = request.sql();

        try {
            if (sql.stripLeading().toLowerCase().startsWith("select")) {
                List<Map<String, Object>> rows = jdbc.queryForList(sql);
                return new RawSqlResult(account, true, null, "OK", rows.size(),
                        rows.size() > 5 ? rows.subList(0, 5) : rows);
            }
            int affected = jdbc.update(sql);
            return new RawSqlResult(account, true, null, "OK", affected, null);
        } catch (Exception e) {
            String msg = rootMessage(e);
            String code = classify(msg);
            return new RawSqlResult(account, false, code, msg, null, null);
        }
    }

    /**
     * 예외 체인을 끝까지 따라가 진짜 원인 메시지를 꾼낸다.
     *
     * ⚠️ 왜 필요한가
     *    query_ro 로 "SELECT * FROM users" 를 실행했더니
     *    Spring 이 BadSqlGrammarException 으로 감싸서 "bad SQL grammar" 로 보였다.
     *    실제 원인은 문법이 아니라 **권한 거부**(SQLSyntaxErrorException 1142)였다.
     *    MySQL 이 권한 오류를 SQLState 42000 으로 내려서 생긴 혼동이다.
     *
     *    바깥 메시지만 로그하면 "SQL 문법 오류" 로 오진한다.
     *    운영에서 원인을 못 찾는 전형적인 패턴.
     */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    private static String classify(String msg) {
        if (msg == null) return "SQL_ERROR";
        String m = msg.toLowerCase();
        if (m.contains("denied")) return "DB_PERMISSION_DENIED";
        if (m.contains("timeout") || m.contains("timed out")) return "QUERY_TIMEOUT";
        if (m.contains("doesn't exist")) return "TABLE_NOT_FOUND";
        return "SQL_ERROR";
    }
}
