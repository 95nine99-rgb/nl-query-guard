package dev.portfolio.nlquery.seed;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 시드 데이터 생성기.
 *
 * 실행:
 *   ./gradlew bootRun --args='--spring.profiles.active=seed'
 *
 * JPA save() 가 아니라 JdbcTemplate.batchUpdate 를 쓰는 이유:
 *   id 전략이 IDENTITY(AUTO_INCREMENT) 라 Hibernate 의 JDBC 배치가 동작하지 않는다.
 *   매 INSERT 마다 생성된 id 를 받아와야 해서 묶을 수가 없다.
 *   5000건이면 왕복 5000번이 된다.
 *
 * 대가: 엔티티 생성자의 검증(Category 의 isBlank 등)이 돌지 않는다.
 *      여기서 넣는 값은 통제된 고정 데이터라 허용한다.
 */
@Component
@Profile("seed")
@RequiredArgsConstructor
public class SeedRunner implements CommandLineRunner {

    private static final int TX_COUNT = 5_000;
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final int DAYS = 31;

    private static final String[] CATEGORIES = {"식비", "교통", "카페", "쇼핑", "의료"};
    private static final String[] EMAILS = {"a@test.com", "b@test.com", "c@test.com"};

    /** 카테고리별 상호명. 자연어 질의 테스트에 쓸 현실적인 값 */
    private static final String[][] TITLES = {
            {"김밥천국", "백반집", "국밥", "편의점 도시락", "치킨"},
            {"지하철", "버스", "택시", "KTX", "주차료"},
            {"스타벅스", "이디야", "투썸플레이스", "메가커피", "빽다방"},
            {"쿠팡", "무신사", "올리브영", "다이소", "네이버쇼핑"},
            {"약국", "내과", "치과", "한의원", "안과"}
    };

    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public void run(String... args) {
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class);
        if (existing != null && existing > 0) {
            System.out.println("[seed] 이미 " + existing + "건이 있어 건너뜁니다. 다시 넣으려면 먼저 지우세요:");
            System.out.println("[seed]   DELETE FROM transactions; DELETE FROM users; DELETE FROM categories;");
            return;
        }

        long start = System.currentTimeMillis();

        jdbc.batchUpdate("INSERT INTO categories (name) VALUES (?)",
                List.of(CATEGORIES).stream().map(c -> new Object[]{c}).toList());
        jdbc.batchUpdate("INSERT INTO users (email) VALUES (?)",
                List.of(EMAILS).stream().map(e -> new Object[]{e}).toList());

        List<Long> categoryIds = jdbc.queryForList("SELECT id FROM categories ORDER BY id", Long.class);
        List<Long> userIds = jdbc.queryForList("SELECT id FROM users ORDER BY id", Long.class);

        RandomGenerator rnd = RandomGenerator.getDefault();
        List<Object[]> rows = new ArrayList<>(TX_COUNT);

        for (int i = 0; i < TX_COUNT; i++) {
            int catIdx = rnd.nextInt(CATEGORIES.length);
            String[] titlePool = TITLES[catIdx];

            LocalDateTime at = FROM
                    .plusDays(rnd.nextInt(DAYS))
                    .plusHours(rnd.nextInt(24))
                    .plusMinutes(rnd.nextInt(60));

            rows.add(new Object[]{
                    userIds.get(rnd.nextInt(userIds.size())),
                    categoryIds.get(catIdx),
                    titlePool[rnd.nextInt(titlePool.length)],
                    1_000 + rnd.nextInt(149_000),
                    Timestamp.valueOf(at)
            });
        }

        jdbc.batchUpdate(
                "INSERT INTO transactions (user_id, category_id, title, amount, transaction_at) VALUES (?, ?, ?, ?, ?)",
                rows);

        long ms = System.currentTimeMillis() - start;
        System.out.println("[seed] users " + userIds.size()
                + " / categories " + categoryIds.size()
                + " / transactions " + TX_COUNT + " 건 삽입 완료 (" + ms + "ms)");
    }
}
