-- V6: create sys_expose_index_job
-- Stores index/backfill job definitions and runtime progress

CREATE TABLE IF NOT EXISTS sys_expose_index_job (
  id BIGSERIAL PRIMARY KEY,
  entity_type VARCHAR(255) NOT NULL,
  class_name VARCHAR(255),
  mappings JSONB NOT NULL,
  ddl_statements TEXT[],
  dry_run BOOLEAN DEFAULT true,
  chunk_size INT DEFAULT 1000,
  status VARCHAR(30) DEFAULT 'PENDING',
  requested_by VARCHAR(100),
  requested_at TIMESTAMP WITH TIME ZONE DEFAULT now(),
  processed_at TIMESTAMP WITH TIME ZONE,
  error TEXT
);

-- Index for quick lookup by entity_type
CREATE INDEX IF NOT EXISTS idx_sys_expose_index_job_entity_type ON sys_expose_index_job(entity_type);
