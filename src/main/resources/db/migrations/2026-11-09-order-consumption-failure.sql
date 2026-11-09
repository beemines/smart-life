-- Additive migration: apply before deploying the order consumption change.
-- Stop legacy producers/consumers and drain in-flight handlers before cutover.
-- Do not roll back to consumers that ignore these terminal rejection records.
-- Rejections are not unpaid/cancelled/refunded business orders.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS tb_voucher_order_failure (
    order_id BIGINT NOT NULL COMMENT '被拒绝的订单ID，幂等主键',
    user_id BIGINT UNSIGNED NOT NULL,
    voucher_id BIGINT UNSIGNED NOT NULL,
    reason VARCHAR(32) NOT NULL COMMENT '库存不足、重复领取或发送失败',
    retained_order_id BIGINT NULL COMMENT '应保留领取资格的已有订单ID',
    compensation_status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending / done / manual',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (order_id),
    KEY idx_failure_compensation (compensation_status),
    KEY idx_failure_user_voucher (user_id, voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
