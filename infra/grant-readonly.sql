-- 8/24(월) D1 · 엔티티가 생성돼 테이블이 만들어진 뒤에 실행한다.
--
--   docker exec -i nlq-mysql mysql -uroot -prootpw nlquery < infra/grant-readonly.sql
--
-- ADR-002 의 방어층 4. 파서를 뚫려도 여기서 막히게 하는 마지막 층이다.
--
-- ⚠️ 절대 하지 말 것:  GRANT SELECT ON nlquery.* TO 'query_ro'@'%';
--    와일드카드를 쓰면 users 테이블까지 열린다. 그러면 "LLM 이 생성한 SQL 이
--    남의 데이터를 훑지 못하게 막는다"는 이 프로젝트의 전제가 통째로 무너진다.
--    반드시 테이블을 하나씩 명시한다.

GRANT SELECT ON nlquery.transactions TO 'query_ro'@'%';
GRANT SELECT ON nlquery.category     TO 'query_ro'@'%';
-- users 는 일부러 주지 않는다.

FLUSH PRIVILEGES;

-- 확인:
--   docker exec -it nlq-mysql mysql -uquery_ro -propw -e "SHOW GRANTS;"
--   → transactions, category 두 줄만 나오고 users 가 없어야 정상
