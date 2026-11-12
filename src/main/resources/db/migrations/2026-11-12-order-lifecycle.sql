SET NAMES utf8mb4;
-- Idempotent voucher columns; use windows are UTC, separate from claim windows.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='tb_voucher' AND column_name='use_begin_time')=0,'ALTER TABLE tb_voucher ADD COLUMN use_begin_time DATETIME NULL, ADD COLUMN use_end_time DATETIME NULL','SELECT 1');
PREPARE lifecycle_ddl FROM @ddl;
EXECUTE lifecycle_ddl;
DEALLOCATE PREPARE lifecycle_ddl;
CREATE TABLE IF NOT EXISTS tb_order_lifecycle (
 order_id BIGINT NOT NULL PRIMARY KEY,
 snapshot_valid TINYINT NOT NULL DEFAULT 0,
 shop_id BIGINT NULL, shop_name VARCHAR(255) NULL, title VARCHAR(255) NULL,
 pay_value BIGINT NULL, actual_value BIGINT NULL,
 pay_deadline DATETIME(6) NULL, use_begin_time DATETIME(6) NULL, use_end_time DATETIME(6) NULL,
 close_reason VARCHAR(32) NULL,
 cancel_accepted TINYINT NOT NULL DEFAULT 0,
 refund_accepted TINYINT NOT NULL DEFAULT 0,
 close_scheduled TINYINT NOT NULL DEFAULT 0,
 close_attempt_at DATETIME(6) NULL,
 close_error VARCHAR(255) NULL,
 schedule_attempt_at DATETIME(6) NULL,
 refund_attempt_at DATETIME(6) NULL,
 KEY idx_close_schedule(close_scheduled,schedule_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='tb_order_lifecycle' AND column_name='close_attempt_at')=0,'ALTER TABLE tb_order_lifecycle ADD COLUMN close_attempt_at DATETIME(6) NULL, ADD COLUMN close_error VARCHAR(255) NULL','SELECT 1');
PREPARE lifecycle_ddl FROM @ddl;
EXECUTE lifecycle_ddl;
DEALLOCATE PREPARE lifecycle_ddl;
CREATE TABLE IF NOT EXISTS tb_order_stock_return (
 order_id BIGINT NOT NULL PRIMARY KEY,
 voucher_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0, last_attempt_at DATETIME(6) NULL,
 last_error VARCHAR(255) NULL,
 KEY idx_stock_return(state,last_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- INSERT IGNORE makes migration capture exactly once, including invalid rows.
-- Legacy deadline derives from original creation, never deployment day.
INSERT IGNORE INTO tb_order_lifecycle(order_id,snapshot_valid,shop_id,shop_name,title,pay_value,actual_value,pay_deadline,use_begin_time,use_end_time)
SELECT o.id,IF(v.id IS NOT NULL AND s.id IS NOT NULL AND v.pay_value>0 AND v.actual_value>=v.pay_value AND v.title<>'' AND ((v.use_begin_time IS NULL AND v.use_end_time IS NULL) OR (v.use_begin_time IS NOT NULL AND v.use_end_time>v.use_begin_time)),1,0),v.shop_id,s.name,v.title,v.pay_value,v.actual_value,
 DATE_ADD(o.create_time,INTERVAL 15 MINUTE),COALESCE(v.use_begin_time,o.create_time),COALESCE(v.use_end_time,DATE_ADD(o.create_time,INTERVAL 30 DAY))
FROM tb_voucher_order o LEFT JOIN tb_voucher v ON v.id=o.voucher_id LEFT JOIN tb_shop s ON s.id=v.shop_id;
