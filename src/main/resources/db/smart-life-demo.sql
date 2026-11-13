-- Optional synthetic demo fixtures. No users, phone numbers or source data.
SET NAMES utf8mb4;
USE smartlife;

INSERT INTO tb_shop_type (id, name, icon, sort)
VALUES (1, 'Cafe', '', 1), (2, 'Bookstore', '', 2)
ON DUPLICATE KEY UPDATE id = VALUES(id);

INSERT INTO tb_shop
    (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours)
VALUES
    (1, 'SmartLife Cafe', 1, '', 'Demo District', '1 Example Street', 113.0, 23.0, 30, 0, 0, 45, '09:00-21:00'),
    (2, 'SmartLife Books', 2, '', 'Demo District', '2 Example Street', 113.1, 23.1, 50, 0, 0, 48, '10:00-22:00')
ON DUPLICATE KEY UPDATE id = VALUES(id);

CREATE TABLE IF NOT EXISTS tb_voucher_batch (
 id BIGINT NOT NULL PRIMARY KEY, voucher_id BIGINT NOT NULL, shop_id BIGINT NOT NULL,
 creator_id BIGINT NOT NULL, request_key VARCHAR(64) NOT NULL, payload_hash CHAR(64) NOT NULL,
 state VARCHAR(24) NOT NULL, error VARCHAR(200), created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_batch_request(creator_id,request_key), KEY idx_batch_shop(shop_id,id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS tb_voucher_batch_row (
 task_id BIGINT NOT NULL,source_row INT NOT NULL,user_text VARCHAR(128) NOT NULL,
 canonical_user_id BIGINT NULL,order_id BIGINT NOT NULL,request_key VARCHAR(64) NOT NULL,
 state VARCHAR(24) NOT NULL,retryable BOOLEAN NOT NULL DEFAULT FALSE,attempts INT NOT NULL DEFAULT 0,
 error VARCHAR(200),last_attempt_at DATETIME(6) NOT NULL DEFAULT '2000-01-01',
 lease_token VARCHAR(36),lease_until DATETIME(6),
 PRIMARY KEY(task_id,source_row), UNIQUE KEY uk_batch_recipient(task_id,canonical_user_id),
 UNIQUE KEY uk_batch_order(order_id), KEY idx_batch_dispatch(state,last_attempt_at,task_id,source_row)
) ENGINE=InnoDB;

-- Actual 10,000-order mine plans scanned all user rows and sorted for LIMIT 21.
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND index_name='idx_order_user_time')=0,'ALTER TABLE tb_voucher_order ADD INDEX idx_order_user_time(user_id,create_time DESC,id DESC)','SELECT 1');
PREPARE order_list_ddl FROM @ddl;
EXECUTE order_list_ddl;
DEALLOCATE PREPARE order_list_ddl;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND index_name='idx_order_user_status_time')=0,'ALTER TABLE tb_voucher_order ADD INDEX idx_order_user_status_time(user_id,status,create_time DESC,id DESC)','SELECT 1');
PREPARE order_list_ddl FROM @ddl;
EXECUTE order_list_ddl;
DEALLOCATE PREPARE order_list_ddl;
