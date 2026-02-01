CREATE TABLE IF NOT EXISTS sys_expose_requests (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  case_instance_id VARCHAR(255) NOT NULL,
  entity_type VARCHAR(255),
  requested_by VARCHAR(255),
  requested_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  status VARCHAR(50) DEFAULT 'PENDING',
  processed_at TIMESTAMP NULL
);
CREATE INDEX IF NOT EXISTS idx_expose_requests_caseid ON sys_expose_requests(case_instance_id);
