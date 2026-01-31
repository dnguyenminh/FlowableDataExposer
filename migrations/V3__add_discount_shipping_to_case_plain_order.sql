-- Adds discount and shipping_fee to case_plain_order (nullable)
ALTER TABLE case_plain_order
  ADD COLUMN discount NUMERIC(19,4),
  ADD COLUMN shipping_fee NUMERIC(19,4);
