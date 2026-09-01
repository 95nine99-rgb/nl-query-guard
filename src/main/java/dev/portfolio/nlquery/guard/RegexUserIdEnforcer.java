package dev.portfolio.nlquery.guard;

public class RegexUserIdEnforcer implements UserIdEnforcer {
    @Override
    public String enforce(String sql, long loginUserId) {
        String result = "";
        sql = sql.replaceAll("--.*", "");
        if (!sql.matches(".*WHERE.*")) {
            throw new SqlGuardException("WHERE절이 없습니다");
        } else if (!sql.matches(".*WHERE user_id.*")) {
            result = sql.replaceFirst("(?i)WHERE", "WHERE user_id=" + loginUserId + " AND");
            return result;
        }

        result = sql.replaceAll("user_id\\s*=\\s*\\d+", "user_id=" + loginUserId);

        return result;
    }
}
