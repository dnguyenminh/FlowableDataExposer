-- Migration: create case_plain_order for 'Order' entity (example)
-- NOTE: tests use hibernate ddl-auto=create; include this SQL for production migration tools (Flyway/LIQUIBASE)

CREATE TABLE case_plain_order (
  id BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
  case_instance_id VARCHAR(255) NOT NULL,
  order_total NUMERIC(19,4),
  plain_payload CLOB,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX ux_case_plain_order_case_instance_id ON case_plain_order(case_instance_id);
