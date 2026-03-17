---
status: testing
phase: 35-join-intersect-except-single-engine-pushdown
source: 35-01-SUMMARY.md, 35-02-SUMMARY.md, 35-03-SUMMARY.md
started: 2026-03-14T22:00:00Z
updated: 2026-03-14T22:00:00Z
---

## Current Test

number: 0
name: Docker build + stack setup
expected: |
  Image builds successfully, Docker Compose stack starts with PG + Oracle + Dremio
awaiting: execution

## Tests

### 0. Docker image build (no CE dependencies)
expected: mvn package + docker build succeed with only OSS code
result: [pending]

### 1. PG JDBC: INNER JOIN same-source pushdown
expected: JOIN appears in PG container log, correct results (employees × departments)
result: [pending]

### 2. PG JDBC: LEFT JOIN same-source pushdown
expected: LEFT OUTER JOIN in PG log, all employees including those with NULL department
result: [pending]

### 3. PG JDBC: RIGHT JOIN same-source pushdown
expected: RIGHT OUTER JOIN in PG log, all departments including those with no employees
result: [pending]

### 4. PG JDBC: FULL OUTER JOIN same-source pushdown
expected: FULL OUTER JOIN in PG log, all rows from both sides
result: [pending]

### 5. PG JDBC: JOIN + WHERE filter pushed together
expected: PG log shows JOIN SQL with WHERE clause containing the filter predicate
result: [pending]

### 6. PG JDBC: JOIN + GROUP BY + SUM
expected: Correct aggregated totals per department (Engineering=505k, Marketing=265k, Sales=308k)
result: [pending]

### 7. PG JDBC: JOIN + GROUP BY + AVG + ORDER BY
expected: Correct averages ordered descending
result: [pending]

### 8. PG JDBC: JOIN + GROUP BY + COUNT
expected: Correct employee counts per department (Engineering=4, Marketing=3, Sales=3)
result: [pending]

### 9. PG ADBC: INNER JOIN same-source pushdown
expected: Same correct results as JDBC mode, PG log shows query executed
result: [pending]

### 10. PG ADBC: LEFT JOIN same-source pushdown
expected: Same results as JDBC LEFT JOIN test
result: [pending]

### 11. PG ADBC: JOIN + GROUP BY + SUM
expected: Same aggregation results as JDBC test 6
result: [pending]

### 12. PG ADBC: JOIN + WHERE + GROUP BY + AVG + ORDER BY (combined)
expected: Filtered + aggregated + sorted results match JDBC equivalent
result: [pending]

### 13. ORA JDBC: INNER JOIN same-source pushdown
expected: JOIN appears in Oracle V$SQL, correct results with uppercase identifiers
result: [pending]

### 14. ORA JDBC: LEFT JOIN same-source pushdown
expected: LEFT OUTER JOIN in V$SQL, all employees
result: [pending]

### 15. ORA JDBC: FULL OUTER JOIN same-source pushdown
expected: FULL OUTER JOIN in V$SQL
result: [pending]

### 16. ORA JDBC: JOIN + GROUP BY + SUM
expected: Same aggregation results as PG (Engineering=505k etc.)
result: [pending]

### 17. ORA JDBC: JOIN + GROUP BY + AVG + ORDER BY
expected: Same ordered averages as PG
result: [pending]

### 18. NEGATIVE: Cross-source JOIN NOT pushed
expected: Query succeeds (Dremio engine handles it), but PG log does NOT contain JOIN keyword
result: [pending]

## Summary

total: 19
passed: 0
issues: 0
pending: 19
skipped: 0

## Gaps

[none yet]
