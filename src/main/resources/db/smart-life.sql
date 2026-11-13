-- SmartLife schema. Contains no user records or source-environment metadata.
SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS smartlife CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE smartlife;

CREATE TABLE `tb_blog`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id` bigint(20) NOT NULL COMMENT '商户id',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题',
  `images` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '探店的照片，最多9张，多张以\",\"隔开',
  `content` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '探店的文字描述',
  `liked` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '点赞数量',
  `comments` int(8) UNSIGNED NULL DEFAULT NULL COMMENT '评论数量',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_blog_comments`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `blog_id` bigint(20) UNSIGNED NOT NULL COMMENT '探店id',
  `parent_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的1级评论id，如果是一级评论，则值为0',
  `answer_id` bigint(20) UNSIGNED NOT NULL COMMENT '回复的评论id',
  `content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '回复的内容',
  `liked` int(8) UNSIGNED NULL DEFAULT NULL COMMENT '点赞数',
  `status` tinyint(1) UNSIGNED NULL DEFAULT NULL COMMENT '状态，0：正常，1：被举报，2：禁止查看',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_follow`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `follow_user_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的用户id',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_seckill_voucher`  (
  `voucher_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的优惠券的id',
  `stock` int(8) NOT NULL COMMENT '库存',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `begin_time` datetime NOT NULL COMMENT '生效时间',
  `end_time` datetime NOT NULL COMMENT '失效时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`voucher_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '秒杀优惠券表，与优惠券是一对一关系' ROW_FORMAT = Compact;

CREATE TABLE `tb_shop`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '商铺名称',
  `type_id` bigint(20) UNSIGNED NOT NULL COMMENT '商铺类型的id',
  `images` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '商铺图片，多个图片以\',\'隔开',
  `area` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '商圈，例如陆家嘴',
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '地址',
  `x` double UNSIGNED NOT NULL COMMENT '经度',
  `y` double UNSIGNED NOT NULL COMMENT '维度',
  `avg_price` bigint(10) UNSIGNED NULL DEFAULT NULL COMMENT '均价，取整数',
  `sold` int(10) UNSIGNED ZEROFILL NOT NULL COMMENT '销量',
  `comments` int(10) UNSIGNED ZEROFILL NOT NULL COMMENT '评论数量',
  `score` int(2) UNSIGNED ZEROFILL NOT NULL COMMENT '评分，1~5分，乘10保存，避免小数',
  `open_hours` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '营业时间，例如 10:00-22:00',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `foreign_key_type`(`type_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_shop_type`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '类型名称',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '图标',
  `sort` int(3) UNSIGNED NULL DEFAULT NULL COMMENT '顺序',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_sign`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `year` year NOT NULL COMMENT '签到的年',
  `month` tinyint(2) NOT NULL COMMENT '签到的月',
  `date` date NOT NULL COMMENT '签到的日期',
  `is_backup` tinyint(1) UNSIGNED NULL DEFAULT NULL COMMENT '是否补签',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_user`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '手机号码',
  `password` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '密码，加密存储',
  `nick_name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '昵称，默认是用户id',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '人物头像',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uniqe_key_phone`(`phone`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_user_info`  (
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '主键，用户id',
  `city` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '城市名称',
  `introduce` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '个人介绍，不要超过128个字符',
  `fans` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '粉丝数量',
  `followee` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '关注的人的数量',
  `gender` tinyint(1) UNSIGNED NULL DEFAULT 0 COMMENT '性别，0：男，1：女',
  `birthday` date NULL DEFAULT NULL COMMENT '生日',
  `credits` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '积分',
  `level` tinyint(1) UNSIGNED NULL DEFAULT 0 COMMENT '会员级别，0~9级,0代表未开通会员',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_voucher`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id` bigint(20) UNSIGNED NULL DEFAULT NULL COMMENT '商铺id',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '代金券标题',
  `sub_title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '副标题',
  `rules` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '使用规则',
  `pay_value` bigint(10) UNSIGNED NOT NULL COMMENT '支付金额，单位是分。例如200代表2元',
  `actual_value` bigint(10) NOT NULL COMMENT '抵扣金额，单位是分。例如200代表2元',
  `type` tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '0,普通券；1,秒杀券',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '1,上架; 2,下架; 3,过期',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_voucher_order`  (
  `id` bigint(20) NOT NULL COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '下单的用户id',
  `voucher_id` bigint(20) UNSIGNED NOT NULL COMMENT '购买的代金券id',
  `pay_type` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '支付方式 1：余额支付；2：支付宝；3：微信',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '订单状态，1：未支付；2：已支付；3：已核销；4：已取消；5：退款中；6：已退款',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `pay_time` timestamp NULL DEFAULT NULL COMMENT '支付时间',
  `use_time` timestamp NULL DEFAULT NULL COMMENT '核销时间',
  `refund_time` timestamp NULL DEFAULT NULL COMMENT '退款时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_order_user_voucher` (`user_id`, `voucher_id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;
-- Additive migration: apply before deploying the order consumption change.
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

SET NAMES utf8mb4;

SET NAMES utf8mb4;

-- Pre-upgrade /voucher/seckill could omit type; its real detail row is authoritative.
UPDATE tb_voucher v JOIN tb_seckill_voucher sv ON sv.voucher_id=v.id
SET v.type=1 WHERE v.type=0;

CREATE TABLE IF NOT EXISTS tb_voucher_publication (
    voucher_id BIGINT UNSIGNED NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    published_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    may_seed_stock TINYINT(1) NOT NULL DEFAULT 0,
    initial_stock INT UNSIGNED NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    last_attempt_at DATETIME NULL,
    last_error VARCHAR(255) NULL,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (voucher_id),
    KEY idx_voucher_publication (state,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Existing activities may validate existing Redis stock, never seed missing stock.
INSERT IGNORE INTO tb_voucher_publication(voucher_id,initial_stock,may_seed_stock)
SELECT voucher_id,stock,0 FROM tb_seckill_voucher;

-- No automatic grants: provision ADMIN/OWNER explicitly after identity checks.
CREATE TABLE IF NOT EXISTS tb_shop_permission (
    user_id BIGINT UNSIGNED NOT NULL,
    shop_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    role VARCHAR(16) NOT NULL,
    PRIMARY KEY (user_id,shop_id,role),
    KEY idx_permission_shop (shop_id),
    CONSTRAINT ck_shop_permission_scope CHECK ((role='ADMIN' AND shop_id=0) OR (role='OWNER' AND shop_id>0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_shop_cache_sync (
    shop_id BIGINT UNSIGNED NOT NULL,
    version BIGINT UNSIGNED NOT NULL,
    synced_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    last_attempt_at DATETIME NULL,
    last_error VARCHAR(255) NULL,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (shop_id),
    KEY idx_shop_sync (synced_version,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_uploaded_file (
    name VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    format VARCHAR(8) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (name),
    KEY idx_uploaded_owner (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


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
