SELECT COUNT_STAR,
       REPLACE(REPLACE(REPLACE(DIGEST_TEXT, '\n', ' '), '\t', ' '), '`', '') AS full_shape
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'zschedule_e2e'
  AND DIGEST_TEXT LIKE '%z_schedule_job_log%'
  AND DIGEST_TEXT LIKE 'UPDATE%'
ORDER BY COUNT_STAR DESC
LIMIT 2;
