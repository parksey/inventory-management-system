#!/usr/bin/env bash
# 빈 DB에서 compose 구성(nginx, 앱 3대, PostgreSQL)을 띄우고 k6 부하를 건 뒤 정합성을 확인한다.
# 사용법: ./load-test/run.sh [load|stress|spike] (기본 load). 정합성이 하나라도 깨지면 exit 1. 레포 어디서 실행해도 된다.
set -euo pipefail
cd "$(dirname "$0")/.."

scenario=${1:-load}
case "$scenario" in
  load|stress|spike) ;;
  *) echo "사용법: $0 [load|stress|spike]" >&2; exit 2 ;;
esac

compose() { docker compose -f infra/compose.yaml "$@"; }
psql_q() { compose exec -T postgres psql -U inventory -d inventory -tA "$@"; }

echo "== 구성 기동 (DB 초기화)"
compose down -v --remove-orphans
compose up -d --build --wait postgres app nginx
mkdir -p build/load-test

echo "== 부하 ($scenario)"
compose run --rm k6 run --quiet -e SCENARIO="$scenario" /scripts/inventory-mixed.js

echo "== 정합성"
violations=$(psql_q -F ': ' < load-test/consistency.sql)
echo "$violations"
applied=$(tr -d '[:space:]' < build/load-test/applied_keys.txt)
ledger=$(psql_q -c 'SELECT COUNT(*) FROM stock_transaction')
echo "반영된 키 ${applied}, 원장 ${ledger}행"

failed=0
if [ -n "$(grep -v ': 0$' <<< "$violations")" ]; then failed=1; fi   # macOS grep은 -qv를 함께 쓰면 결과가 틀린다
if [ "$applied" != "$ledger" ]; then echo "반영된 키 수와 원장 행 수가 다르다"; failed=1; fi
[ "$failed" = 0 ] && echo "== 정합성 통과" || echo "== 정합성 실패"
exit "$failed"
