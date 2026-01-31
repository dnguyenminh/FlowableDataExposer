-- Migration: add normalized plain columns to case_plain_order for Order entity
-- Adds customer_id, customer_name, priority, approval_status, decision_reason

ALTER TABLE case_plain_order
  ADD COLUMN customer_id VARCHAR(128),
  ADD COLUMN customer_name VARCHAR(255),
  ADD COLUMN order_priority VARCHAR(50),
  ADD COLUMN approval_status VARCHAR(50),
  ADD COLUMN decision_reason VARCHAR(1024);

CREATE INDEX idx_case_plain_order_customer_id ON case_plain_order(customer_id);
CREATE INDEX idx_case_plain_order_approval_status ON case_plain_order(approval_status);
