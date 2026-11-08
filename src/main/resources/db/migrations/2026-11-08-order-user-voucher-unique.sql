-- One-time migration for an existing Smart Life database.
-- Select the target database in the MySQL client before executing this file.
-- Stop order writers during maintenance; do NOT run the client with --force.
-- Inspect and reconcile any reported duplicates before retrying this migration.
-- No business rows are deleted or rewritten by this script.

SELECT user_id, voucher_id, COUNT(*) AS duplicate_count
FROM tb_voucher_order
GROUP BY user_id, voucher_id
HAVING COUNT(*) > 1;

-- MySQL rejects the index build if duplicates still exist, preserving the rows.
-- This enforces one record per pair, INCLUDING cancelled/refunded orders.
-- Apply once: an already installed key requires no further migration.
ALTER TABLE tb_voucher_order
    ADD CONSTRAINT uk_order_user_voucher UNIQUE (user_id, voucher_id);
