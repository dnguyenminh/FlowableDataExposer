-- Migration: add status, error_message, and version columns to sys_case_data_store
-- Idempotent where supported (uses IF NOT EXISTS which is supported by H2 and Postgres)

ALTER TABLE sys_case_data_store ADD COLUMN IF NOT EXISTS status VARCHAR(50) DEFAULT 'PENDING';
ALTER TABLE sys_case_data_store ADD COLUMN IF NOT EXISTS error_message VARCHAR(1024);
ALTER TABLE sys_case_data_store ADD COLUMN IF NOT EXISTS version INTEGER DEFAULT 1;

-- Backfill existing rows where needed (set defaults explicitly)
UPDATE sys_case_data_store SET status = 'PENDING' WHERE status IS NULL;
UPDATE sys_case_data_store SET version = 1 WHERE version IS NULL;
