-- Migration: create sys_case_data_store (append-only case blob store)
-- Dev/DBA: idempotent SQL for H2/Postgres-compatible engines where possible
CREATE TABLE IF NOT EXISTS sys_case_data_store (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  case_instance_id VARCHAR(255) NOT NULL,
  entity_type VARCHAR(255),
  payload CLOB,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sys_case_data_store_case_instance_id ON sys_case_data_store(case_instance_id);
