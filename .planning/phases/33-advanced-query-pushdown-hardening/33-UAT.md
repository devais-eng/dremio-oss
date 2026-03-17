---
status: testing
phase: 33-advanced-query-pushdown-hardening
source: [33-01-SUMMARY.md, 33-02-SUMMARY.md, 33-03-SUMMARY.md]
started: 2026-03-13T16:15:00Z
updated: 2026-03-13T16:15:00Z
---

## Current Test
<!-- OVERWRITE each test - shows where we are -->

number: 1
name: Build Dremio OSS without CE JDBC plugin
expected: |
  Maven build succeeds with community-build profile. CE JDBC plugin
  is excluded from dac/daemon/pom.xml. Distribution tarball is produced.
  JDBC plugin JARs (jdbc-base, jdbc-postgresql, jdbc-oracle) are included.
awaiting: user response

## Tests

### 1. Build Dremio OSS without CE JDBC plugin
expected: Maven build succeeds with CE JDBC plugin excluded. Distribution tarball produced with our JDBC plugin JARs.
result: [pending]

### 2. Build Docker image and deploy stack
expected: Docker image built from tarball. docker-compose starts Dremio, PostgreSQL, Oracle. All 3 containers healthy.
result: [pending]

### 3. Create POSTGRES_DB and ORACLE_DB sources
expected: Both sources created via Dremio REST API. Schema browseable.
result: [pending]

### 4. PG: WHERE pushdown with bind parameters
expected: Run `SELECT name, salary FROM pg_test.public.employees WHERE salary > 100000`. PostgreSQL log shows WHERE clause pushed to source (not full table scan + in-engine filter).
result: [pending]

### 5. PG: Projection pushdown
expected: Run `SELECT name FROM pg_test.public.employees`. PostgreSQL log shows only "name" column in SELECT, not SELECT *.
result: [pending]

### 6. PG: LIMIT pushdown
expected: Run `SELECT * FROM pg_test.public.employees LIMIT 2`. PostgreSQL log shows LIMIT 2 in query.
result: [pending]

### 7. PG: ORDER BY pushdown
expected: Run `SELECT name, salary FROM pg_test.public.employees ORDER BY salary DESC`. PostgreSQL log shows ORDER BY in pushed query.
result: [pending]

### 8. PG: TopN pushdown (ORDER BY + LIMIT)
expected: Run `SELECT name, salary FROM pg_test.public.employees ORDER BY salary DESC LIMIT 2`. PostgreSQL log shows both ORDER BY and LIMIT in same query.
result: [pending]

### 9. PG: GROUP BY + COUNT aggregation pushdown
expected: Run `SELECT department, COUNT(*) AS cnt FROM pg_test.public.employees GROUP BY department`. PostgreSQL log shows GROUP BY + COUNT pushed to source.
result: [pending]

### 10. PG: GROUP BY + AVG aggregation pushdown
expected: Run `SELECT department, AVG(salary) AS avg_sal FROM pg_test.public.employees GROUP BY department`. PostgreSQL log shows GROUP BY + AVG pushed to source.
result: [pending]

### 11. PG: Combined WHERE + ORDER BY + LIMIT
expected: Run `SELECT name, salary FROM pg_test.public.employees WHERE salary > 90000 ORDER BY salary ASC LIMIT 2`. PostgreSQL log shows WHERE + ORDER BY + LIMIT all pushed in single query.
result: [pending]

### 12. Oracle: WHERE pushdown
expected: Run `SELECT NAME, PRICE FROM oracle_test.TESTUSER.PRODUCTS WHERE PRICE > 20`. Oracle V$SQL shows WHERE clause pushed.
result: [pending]

### 13. Oracle: LIMIT pushdown (FETCH FIRST, not LIMIT)
expected: Run `SELECT * FROM oracle_test.TESTUSER.PRODUCTS LIMIT 2`. Oracle V$SQL shows FETCH FIRST 2 ROWS ONLY — NO "LIMIT" keyword.
result: [pending]

### 14. Oracle: ORDER BY pushdown
expected: Run `SELECT NAME, PRICE FROM oracle_test.TESTUSER.PRODUCTS ORDER BY PRICE ASC`. Oracle V$SQL shows ORDER BY pushed.
result: [pending]

### 15. Oracle: TopN pushdown (ORDER BY + FETCH FIRST)
expected: Run `SELECT NAME, PRICE FROM oracle_test.TESTUSER.PRODUCTS ORDER BY PRICE DESC LIMIT 2`. Oracle V$SQL shows ORDER BY + FETCH FIRST (not LIMIT).
result: [pending]

### 16. Oracle: GROUP BY + COUNT aggregation pushdown
expected: Run `SELECT COUNT(*) FROM oracle_test.TESTUSER.PRODUCTS`. Oracle V$SQL shows COUNT pushed to source.
result: [pending]

### 17. Cross-source join
expected: Run `SELECT e.name, p.name AS product FROM pg_test.public.employees e CROSS JOIN oracle_test.TESTUSER.PRODUCTS p WHERE e.department = 'Engineering' ORDER BY e.name, p.name`. Each side pushes down independently — PG gets WHERE department = 'Engineering', Oracle gets its own query.
result: [pending]

## Summary

total: 17
passed: 0
issues: 0
pending: 17
skipped: 0

## Gaps

[none yet]
