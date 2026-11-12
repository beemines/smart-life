package com.bee.smartlife.service.impl;

import com.bee.smartlife.entity.*;
import com.bee.smartlife.mapper.*;
import com.bee.smartlife.security.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class VoucherPersistence {
    private final VoucherMapper vouchers;
    private final SeckillVoucherMapper stocks;
    private final JdbcTemplate jdbc;
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public Voucher load(long id){return vouchers.selectById(id);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void create(Voucher voucher,boolean seckill) {
        if(jdbc.queryForList("SELECT id FROM tb_shop WHERE id=? FOR SHARE",Long.class,voucher.getShopId()).isEmpty())throw ApiException.badRequest("店铺不存在");
        if(voucher.getUseBeginTime()==null){
            var now=jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)",java.time.LocalDateTime.class);
            voucher.setUseBeginTime(now);voucher.setUseEndTime(now.plusDays(30));
        }
        if(vouchers.insert(voucher)!=1)throw new IllegalStateException("优惠券保存失败");
        if(!seckill)return;
        var stock=new SeckillVoucher().setVoucherId(voucher.getId()).setStock(voucher.getStock())
                .setBeginTime(voucher.getBeginTime()).setEndTime(voucher.getEndTime());
        if(stocks.insert(stock)!=1)throw new IllegalStateException("秒杀信息保存失败");
        jdbc.update("INSERT INTO tb_voucher_publication(voucher_id,version,published_version,may_seed_stock,initial_stock,state) VALUES (?,1,0,1,?,'PENDING')",voucher.getId(),voucher.getStock());
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void changeStatus(Voucher existing,int status) {
        jdbc.update("UPDATE tb_voucher SET status=? WHERE id=?",status,existing.getId());
        if(Integer.valueOf(1).equals(existing.getType())) {
            jdbc.update("INSERT INTO tb_voucher_publication(voucher_id,version,published_version,may_seed_stock,initial_stock,state) SELECT voucher_id,1,0,0,stock,'PENDING' FROM tb_seckill_voucher WHERE voucher_id=? ON DUPLICATE KEY UPDATE version=version+1,state='PENDING',last_error=NULL",existing.getId());
        }
    }
}
