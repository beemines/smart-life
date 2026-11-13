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
