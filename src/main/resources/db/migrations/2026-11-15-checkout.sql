SET NAMES utf8mb4;
-- Additive and repeatable: historical voucher rules remain NULL (legacy redeem only).
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='tb_shop' AND column_name='checkout_rule_version')=0,'ALTER TABLE tb_shop ADD COLUMN checkout_rule_version BIGINT NOT NULL DEFAULT 0','SELECT 1');
PREPARE checkout_ddl FROM @ddl; EXECUTE checkout_ddl; DEALLOCATE PREPARE checkout_ddl;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='tb_voucher' AND column_name='checkout_enabled')=0,'ALTER TABLE tb_voucher ADD COLUMN checkout_enabled TINYINT NULL, ADD COLUMN checkout_threshold BIGINT NULL, ADD COLUMN checkout_stack_merchant TINYINT NULL, ADD COLUMN checkout_stack_platform TINYINT NULL','SELECT 1');
PREPARE checkout_ddl FROM @ddl; EXECUTE checkout_ddl; DEALLOCATE PREPARE checkout_ddl;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='tb_order_lifecycle' AND column_name='checkout_enabled')=0,'ALTER TABLE tb_order_lifecycle ADD COLUMN checkout_enabled TINYINT NULL, ADD COLUMN checkout_threshold BIGINT NULL, ADD COLUMN checkout_stack_merchant TINYINT NULL, ADD COLUMN checkout_stack_platform TINYINT NULL','SELECT 1');
PREPARE checkout_ddl FROM @ddl; EXECUTE checkout_ddl; DEALLOCATE PREPARE checkout_ddl;

CREATE TABLE IF NOT EXISTS tb_checkout_bill (
 id BIGINT NOT NULL PRIMARY KEY, owner_user_id BIGINT NOT NULL, shop_id BIGINT NOT NULL,
 shop_name VARCHAR(255) NOT NULL, request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 original_total BIGINT NOT NULL, original_excluded BIGINT NOT NULL,
 total BIGINT NOT NULL, excluded BIGINT NOT NULL, amount_version BIGINT NOT NULL DEFAULT 0,
 state VARCHAR(16) NOT NULL DEFAULT 'DRAFT', confirmed_by BIGINT NULL,
 created_at DATETIME(6) NOT NULL, confirmed_at DATETIME(6) NULL, pay_deadline DATETIME(6) NULL,
 quote_json JSON NULL, settlement_json JSON NULL, paid_at DATETIME(6) NULL, reversed_at DATETIME(6) NULL,
 UNIQUE KEY uk_checkout_request(owner_user_id,request_key),
 KEY idx_checkout_owner_time(owner_user_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_checkout_promotion (
 id BIGINT NOT NULL PRIMARY KEY, shop_id BIGINT NOT NULL, title VARCHAR(80) NOT NULL,
 scope VARCHAR(16) NOT NULL, kind VARCHAR(24) NOT NULL,
 threshold BIGINT NOT NULL, reduction BIGINT NOT NULL, payable_bps INT NOT NULL, cap BIGINT NOT NULL,
 priority INT NOT NULL DEFAULT 0, stack_merchant TINYINT NOT NULL DEFAULT 0,
 stack_voucher TINYINT NOT NULL DEFAULT 0, stack_platform TINYINT NOT NULL DEFAULT 0,
 begin_time DATETIME(6) NOT NULL, end_time DATETIME(6) NOT NULL,
 active TINYINT NOT NULL DEFAULT 1, version BIGINT NOT NULL DEFAULT 1,
 KEY idx_checkout_rule_shop(shop_id,scope,active,end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_checkout_coupon (
 id BIGINT NOT NULL PRIMARY KEY, rule_id BIGINT NOT NULL, owner_user_id BIGINT NOT NULL,
 shop_id BIGINT NOT NULL, request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 rule_json JSON NOT NULL, begin_time DATETIME(6) NOT NULL, end_time DATETIME(6) NOT NULL,
 used_bill_id BIGINT NULL, revoked TINYINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_checkout_grant(owner_user_id,request_key),
 KEY idx_checkout_coupon_owner(owner_user_id,shop_id,end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_checkout_voucher_use (
 order_id BIGINT NOT NULL PRIMARY KEY, bill_id BIGINT NOT NULL,
 used_at DATETIME(6) NOT NULL, UNIQUE KEY uk_checkout_bill_voucher(bill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_checkout_reversal (
 bill_id BIGINT NOT NULL PRIMARY KEY, actor_user_id BIGINT NOT NULL,
 reason VARCHAR(255) NOT NULL, refund_amount BIGINT NOT NULL,
 result_json JSON NOT NULL, created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
