-- Additive migration; stop old producers/consumers before coordinated cutover.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS tb_voucher_order_request (
    order_id BIGINT NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    voucher_id BIGINT UNSIGNED NOT NULL,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PREPARING',
    reason VARCHAR(32) NULL,
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    last_error VARCHAR(255) NULL,
    last_attempt_at DATETIME NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (order_id),
    UNIQUE KEY uk_order_request_owner_key (user_id, request_key),
    KEY idx_request_recovery (state, update_time),
    KEY idx_request_voucher (voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_voucher_order_recovery (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    voucher_id BIGINT UNSIGNED NOT NULL,
    action VARCHAR(32) NOT NULL,
    before_state VARCHAR(32) NOT NULL,
    after_state VARCHAR(32) NULL,
    outcome VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    detail VARCHAR(255) NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_recovery_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
