package com.bee.smartlife.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.bee.smartlife.entity.Shop;
import com.bee.smartlife.utils.CacheClient;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.redisson.api.RLock;
import org.redisson.client.RedisException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import static com.bee.smartlife.utils.RedisConstants.*;

@Slf4j
@Component
public class ShopCache {
    private final RedissonClient redisson;
    private final StringRedisTemplate redis;
    private final CacheClient client;
    private final JdbcTemplate jdbc;
    private final Cache<Long,String> local;
    private RBloomFilter<Long> bloom;
    private volatile boolean bloomReady;
    public ShopCache(RedissonClient redisson,StringRedisTemplate redis,CacheClient client,JdbcTemplate jdbc,
                     @Value("${smartlife.cache.local-ttl-ms:180000}") long ttl) {
        this.redisson=redisson;this.redis=redis;this.client=client;this.jdbc=jdbc;
        local=Caffeine.newBuilder().maximumSize(10000).expireAfterWrite(Duration.ofMillis(ttl)).build();
    }
    @PostConstruct public void initialize() {
        bloom=redisson.getBloomFilter("bf:shop:id");
        try {bloom.tryInit(100000L,.01);jdbc.queryForList("SELECT id FROM tb_shop",Long.class).forEach(bloom::add);bloomReady=true;}
        catch(RuntimeException failure){log.warn("店铺布隆暂不可用，查询回源SQL error={}",failure.getClass().getSimpleName());}
    }
    public RLock lock(Long id){return redisson.getLock("lock:shop-write:"+id);}
    public boolean pending(Long id){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM tb_shop_cache_sync WHERE shop_id=? AND version>synced_version)",Boolean.class,id));}
    public Shop find(Long id,Supplier<Shop> load) {
        String hit=local.getIfPresent(id);
        if(StrUtil.isNotBlank(hit))return JSONUtil.toBean(hit,Shop.class);
        RLock lock=lock(id);boolean held=false;
        try {
            held=lock.tryLock(3,TimeUnit.SECONDS);
            if(!held)return load.get(); // No unguarded cache fill on contention.
            hit=local.getIfPresent(id);
            if(StrUtil.isNotBlank(hit))return JSONUtil.toBean(hit,Shop.class);
            // A committed write with incomplete external sync is authoritative.
            // Check before BOTH positive Redis hits and cached empty values.
            if(pending(id))return load.get();
            Shop shop;
            try {
                shop=client.queryWithBloomAndPassThrough(CACHE_SHOP_KEY,id,Shop.class,ignored->load.get(),
                        CACHE_SHOP_TTL,TimeUnit.MINUTES,ignored->!bloomReady||bloom.contains(id));
            } catch(RuntimeException unavailable) {
                log.warn("店铺缓存读取失败，回源SQL shopId={} error={}",id,unavailable.getClass().getSimpleName());
                return load.get();
            }
            // The shared lock protects this final local fill as well as Redis fill.
            if(shop!=null)local.put(id,JSONUtil.toJsonStr(shop));
            return shop;
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new IllegalStateException("店铺查询被中断",interrupted);
        } catch(RedisException unavailable) {return load.get();}
        finally {if(held)try{lock.unlock();}catch(RedisException unavailable){log.warn("店铺锁等待连接恢复 shopId={}",id);}}
    }
    public void evictLocal(Long id){local.invalidate(id);}
    public void invalidateShared(Long id) {
        evictLocal(id);
        bloom.add(id);
        redis.delete(CACHE_SHOP_KEY+id);
    }
}
