package dev.portfolio.nlquery.guard;

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


public class JsqlUserIdEnforcer implements UserIdEnforcer {
    @Override
    public String enforce(String sql, long loginUserId) {
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (!(stmt instanceof Select)) {
                throw new SqlGuardException("SELECT 가 아니다");
            }

            PlainSelect ps = (PlainSelect) stmt;
            Expression where = ps.getWhere();

            Expression cond =
                    new EqualsTo(
                            new Column("user_id"),
                            new LongValue(loginUserId)
                    );
            ps.setWhere(where == null ? cond : new AndExpression(cond, where));
            return stmt.toString();


        } catch (JSQLParserException e) {
            throw new SqlGuardException("파싱할 수 없는 SQL");
        }


    }
}
