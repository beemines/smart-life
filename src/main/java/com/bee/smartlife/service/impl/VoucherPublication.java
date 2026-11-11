package com.bee.smartlife.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.api.RLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherPublication {
    private final JdbcTemplate jdbc;
    private final RedissonClient redisson;
    private final VoucherStockPublisher stocks;
    public RLock lock(long id){return redisson.getLock("lock:voucher-activity:"+id);}
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public boolean tryPublish(long id) {
        var lock=lock(id);boolean held=false;
        try {
            held=lock.tryLock(3,TimeUnit.SECONDS);if(!held)return false;
            var rows=jdbc.queryForList("SELECT version,published_version,may_seed_stock,initial_stock,state FROM tb_voucher_publication WHERE voucher_id=?",id);
            if(rows.isEmpty())return false;
            var row=rows.getFirst();
            boolean alreadyReady="READY".equals(row.get("state"));
            if(!alreadyReady&&!"PENDING".equals(row.get("state")))return false;
            long version=((Number)row.get("version")).longValue();
            jdbc.update("UPDATE tb_voucher_publication SET attempts=attempts+1,last_attempt_at=CURRENT_TIMESTAMP WHERE voucher_id=?",id);
            Object seedValue=row.get("may_seed_stock");
            boolean seed=!alreadyReady&&(Boolean.TRUE.equals(seedValue)||(seedValue instanceof Number number&&number.intValue()==1));
            long result=stocks.publish(id,seed,((Number)row.get("initial_stock")).intValue());
            if(result!=0) {
                jdbc.update("UPDATE tb_voucher_publication SET state='REVIEW',may_seed_stock=0,last_error=? WHERE voucher_id=? AND version=?","STOCK_REQUIRES_REVIEW_"+result,id,version);
                return false;
            }
            if(alreadyReady)return true;
            return jdbc.update("UPDATE tb_voucher_publication SET state='READY',published_version=?,may_seed_stock=0,last_error=NULL WHERE voucher_id=? AND version=? AND state='PENDING'",version,id,version)==1;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
        catch(Exception unavailable) {
            try{jdbc.update("UPDATE tb_voucher_publication SET last_error=? WHERE voucher_id=?",unavailable.getClass().getSimpleName(),id);}catch(RuntimeException ignored){log.warn("活动发布等待数据库恢复 voucherId={}",id);}
            log.warn("活动发布待重试 voucherId={} error={}",id,unavailable.getClass().getSimpleName());return false;
        }finally{if(held)try{lock.unlock();}catch(RuntimeException ignored){log.warn("活动发布锁等待恢复 voucherId={}",id);}}
    }
    public void retryPending(int limit) {
        if(limit<1||limit>500)throw new IllegalArgumentException("同步批次不正确");
        for(long id:jdbc.queryForList("SELECT voucher_id FROM tb_voucher_publication WHERE state='PENDING' AND (last_attempt_at IS NULL OR last_attempt_at<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 15 SECOND)) ORDER BY update_time LIMIT "+limit,Long.class))tryPublish(id);
    }
}
