-- Ensures the budget_insights schema exists before Hibernate runs DDL.
-- Executed by spring.sql.init on every startup (idempotent).
CREATE SCHEMA IF NOT EXISTS budget_insights;
