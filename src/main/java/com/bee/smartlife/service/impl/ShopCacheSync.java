package com.bee.smartlife.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShopCacheSync {
    public record Change(long shopId,long version) {}
    private final ShopCache cache;
    private final JdbcTemplate jdbc;
    private final DefaultMQProducer producer;
    private final ObjectMapper json;
    @Value("${rocketmq.shop-cache-topic:smartlife-shop-cache-change}") private String topic;

    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public boolean trySync(Long id) {
        var lock=cache.lock(id);boolean held=false;
        try {
            held=lock.tryLock(3,TimeUnit.SECONDS);if(!held)return false;
            var rows=jdbc.queryForList("SELECT version,synced_version FROM tb_shop_cache_sync WHERE shop_id=?",id);
            if(rows.isEmpty())return true;
            long version=((Number)rows.getFirst().get("version")).longValue();
            if(version<=((Number)rows.getFirst().get("synced_version")).longValue())return true;
            jdbc.update("UPDATE tb_shop_cache_sync SET last_attempt_at=CURRENT_TIMESTAMP,attempts=attempts+1 WHERE shop_id=?",id);
            cache.invalidateShared(id);
            var sent=producer.send(new Message(topic,json.writeValueAsBytes(new Change(id,version))));
            if(sent==null||sent.getSendStatus()!=SendStatus.SEND_OK)throw new IllegalStateException("缓存失效广播未确认");
            jdbc.update("UPDATE tb_shop_cache_sync SET synced_version=?,last_error=NULL WHERE shop_id=? AND version=?",version,id,version);
            return true;
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
        catch(Exception unavailable) {
            cache.evictLocal(id);
            try{jdbc.update("UPDATE tb_shop_cache_sync SET last_error=? WHERE shop_id=?",unavailable.getClass().getSimpleName(),id);}catch(RuntimeException ignored){log.warn("店铺同步审计等待数据库恢复 shopId={}",id);}
            log.warn("店铺缓存同步待重试 shopId={} error={}",id,unavailable.getClass().getSimpleName());return false;
        } finally {if(held)try{lock.unlock();}catch(RuntimeException ignored){log.warn("店铺同步锁等待连接恢复 shopId={}",id);}}
    }
    public void retryPending(int limit) {
        if(limit<1||limit>500)throw new IllegalArgumentException("同步批次不正确");
        for(Long id:jdbc.queryForList("SELECT shop_id FROM tb_shop_cache_sync WHERE version>synced_version AND (last_attempt_at IS NULL OR last_attempt_at<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 15 SECOND)) ORDER BY update_time LIMIT "+limit,Long.class))trySync(id);
    }
}
