#!/bin/bash
# 악의적 SQL 12종 + 정상 질의 3종 실행.
#
# ⚠️ 실행 순서가 중요하다.
#    처음엔 1번(DROP)을 먼저 돌렸더니 테이블이 사라져서
#    2~9번이 전부 TABLE_NOT_FOUND 로 나왔다. 차단이 아니라 측정 오염이었다.
#    → 비파괴 테스트를 먼저, 파괴 테스트는 맨 뒤에. 파괴 후에는 매번 복구한다.
#
# 사전 조건: ./gradlew bootRun --args='--spring.profiles.active=unguarded'

DOCKER=/Applications/Docker.app/Contents/Resources/bin/docker
URL=http://localhost:8080/api/debug/raw-sql
ACCOUNT="${1:-app}"
BACKUP=backup.sql

restore() {
  $DOCKER exec -i nlq-mysql mysql -uroot -prootpw nlquery < "$BACKUP" 2>/dev/null
}

check_rows() {
  $DOCKER exec nlq-mysql mysql -uapp -papppw nlquery -N -B \
    -e "SELECT COUNT(*) FROM transactions;" 2>/dev/null || echo "테이블없음"
}

run() {
  local num="$1" name="$2" sql="$3"
  local body res ok code rows
  body=$(jq -cn --arg s "$sql" --arg a "$ACCOUNT" '{sql:$s, account:$a}')
  res=$(curl -s -X POST "$URL" -H 'Content-Type: application/json' -d "$body")
  ok=$(echo "$res"   | jq -r '.success')
  code=$(echo "$res" | jq -r '.errorCode // "-"')
  rows=$(echo "$res" | jq -r '.rowCount // "-"')
  if [ "$ok" = "true" ]; then
    printf "%-3s %-20s \033[31m통과(뚫림)\033[0m  rows=%s\n" "$num" "$name" "$rows"
  else
    printf "%-3s %-20s \033[32m차단\033[0m        %s\n" "$num" "$name" "$code"
  fi
}

echo "=============================================="
echo " 계정: $ACCOUNT"
echo "=============================================="

if [ ! -f "$BACKUP" ]; then echo "❌ $BACKUP 없음. 먼저 백업하세요."; exit 1; fi
restore
echo " 시작 시점 transactions: $(check_rows) 건"
echo

echo "--- A. 조회형 공격 (데이터 안 건드림) ---"
run 5  "주석우회"        "SELECT * FROM transactions WHERE user_id=1 -- AND category_id=1"
run 6  "UNION인젝션"     "SELECT id,title,amount,transaction_at,category_id FROM transactions UNION SELECT id,email,1,now(),1 FROM users"
run 7  "다른 user_id"    "SELECT * FROM transactions WHERE user_id = 2"
run 8  "시스템테이블"      "SELECT table_name FROM information_schema.tables"
run 9  "서브쿼리"        "SELECT * FROM transactions WHERE user_id IN (SELECT id FROM users)"
run 11 "대소문자우회"      "SeLeCt  *  FrOm    transactions"
run 12 "주석분할"        "SEL/**/ECT * FROM transactions"
run 10 "SLEEP(10)"      "SELECT SLEEP(10)"

echo
echo "--- B. 정상 질의 3종 (막히면 안 됨) ---"
run N1 "합계"           "SELECT SUM(amount) FROM transactions WHERE user_id=1 AND category_id=1"
run N2 "비싼거래 5건"     "SELECT * FROM transactions WHERE user_id=1 ORDER BY amount DESC LIMIT 5"
run N3 "카테고리별 합계"   "SELECT category_id, SUM(amount) FROM transactions WHERE user_id=1 GROUP BY category_id"

echo
echo "--- C. 파괴형 공격 (매번 복구) ---"
run 3  "UPDATE"         "UPDATE transactions SET amount = 0"
echo "     → amount=0 인 행: $($DOCKER exec nlq-mysql mysql -uapp -papppw nlquery -N -B -e "SELECT COUNT(*) FROM transactions WHERE amount=0;" 2>/dev/null)"
restore

run 2  "DELETE"         "DELETE FROM transactions"
echo "     → 남은 행: $(check_rows)"
restore

run 4  "다중구문"        "SELECT * FROM transactions; DROP TABLE users"
restore

run 1  "DROP"           "DROP TABLE transactions"
echo "     → 남은 행: $(check_rows)"
restore
echo "     → 복구 후: $(check_rows) 건"
