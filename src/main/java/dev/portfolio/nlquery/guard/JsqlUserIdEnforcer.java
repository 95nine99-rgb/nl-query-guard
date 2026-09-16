package dev.portfolio.nlquery.guard;

import dev.portfolio.nlquery.common.error.ErrorCode;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

/**
 * user_id 를 서버가 강제로 주입한다. ADR-002 방어층 1·3.
 *
 * 정책
 *   ⑧ LLM 이 user_id 조건을 쓰면 거부한다. 프롬프트에서 금지했으므로 계약 위반이다.
 *      거부는 사용자 응답이 아니라 재생성 신호다.
 *   ⑨ WHERE 가 없으면 만들어 붙인다. "전부 보여줘" 는 정상 요청이다.
 */
@Component
public class JsqlUserIdEnforcer implements UserIdEnforcer {

    private static final String USER_ID = "user_id";

    @Override
    public String enforce(String sql, long loginUserId) {
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);

            // 방어층 1 — SELECT 외 구문은 여기서 전부 막힌다.
            // 정규식이었다면 DROP|DELETE|UPDATE|... 를 나열해야 했고 빠뜨리면 뚫린다.
            if (!(stmt instanceof Select)) {
                throw new SqlGuardException(ErrorCode.NOT_SELECT);
            }

            PlainSelect ps = (PlainSelect) stmt;
            Expression where = ps.getWhere();

            // 방어층 3 — 계약 위반 탐지
            if (containsUserId(where)) {
                throw new SqlGuardException(ErrorCode.LLM_WROTE_USER_ID);
            }

            Expression cond = new EqualsTo(new Column(USER_ID), new LongValue(loginUserId));
            ps.setWhere(where == null ? cond : new AndExpression(cond, where));

            return stmt.toString();

        } catch (JSQLParserException e) {
            // fail-closed. 파싱할 수 없으면 원본을 반환하지 않는다.
            throw new SqlGuardException(ErrorCode.UNPARSEABLE_SQL);
        }
    }

    /**
     * 파싱된 표현식 전체에서 user_id 참조를 찾는다.
     *
     * ⚠️ 왜 AST 재귀가 아니라 문자열 검사인가 (2026-09-02)
     *
     *   처음에는 Expression 타입을 재귀로 순회했다.
     *
     *     if (expr instanceof Column c)           → 이름 비교
     *     if (expr instanceof BinaryExpression b) → 좌·우 재귀
     *     return false;                            → 나머지는 통과
     *
     *   그런데 LLM 이 만든 SQL 이 구멍을 드러냈다.
     *
     *     WHERE amount > 30000 AND category_id IN (SELECT id FROM categories ...)
     *
     *   InExpression 은 BinaryExpression 이 아니라 마지막 return false 로 빠진다.
     *   Between · NotExpression · Parenthesis 도 마찬가지다.
     *   실제로 "WHERE user_id IN (SELECT 2)" 가 탐지를 통과했다.
     *
     *   타입을 하나씩 추가하는 것은 **블랙리스트**다. 정규식 때와 같은 함정이고,
     *   이 프로젝트가 채택한 "허용할 것을 지정한다" 원칙과도 반대다.
     *
     *   파싱이 끝난 표현식은 주석이 제거되고 정규화된 상태이므로,
     *   여기서의 문자열 검사는 주석·공백·대소문자 우회에 안전하다.
     *   그리고 어느 타입에 숨어 있든 놓치지 않는다.
     *
     * ⚠️ 알려진 한계
     *   문자열 리터럴에 user_id 가 들어가면 오탐이다.
     *   예: WHERE title = 'user_id 관련 지출'
     *   현재 도메인(거래내역 제목)에서 발생 확률이 낮다고 판단해 감수한다.
     *   docs/06-retro.md 남은 과제 참조.
     */
    private boolean containsUserId(Expression where) {
        return where != null
                && where.toString().toLowerCase().contains(USER_ID);
    }
}
