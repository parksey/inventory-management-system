-- 쿼리별 호출 수, 누적 시간: SELECT calls, total_exec_time, query FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 10;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
