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
