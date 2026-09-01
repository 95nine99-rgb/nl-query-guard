package dev.portfolio.nlquery.guard;

import net.sf.jsqlparser.JSQLParserException;

public interface UserIdEnforcer {
    /**
     * @throws SqlGuardException 주입할 수 없는 SQL이면 던진다
     */
    String enforce(String sql, long loginUserId);
}
