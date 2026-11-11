package com.bee.smartlife;

import org.springframework.jdbc.core.JdbcTemplate;

/** SQL/Redis stock are deliberately controlled by each P0 fault fixture. */
final class OrderTestActivity {
    static void ready(JdbcTemplate jdbc,long id) {
        jdbc.update("UPDATE tb_seckill_voucher SET begin_time='2000-01-01 00:00:00',end_time='2100-01-01 00:00:00' WHERE voucher_id=?",id);
        jdbc.update("INSERT INTO tb_voucher_publication(voucher_id,version,published_version,may_seed_stock,initial_stock,state) SELECT voucher_id,1,1,0,stock,'READY' FROM tb_seckill_voucher WHERE voucher_id=?",id);
    }
}
