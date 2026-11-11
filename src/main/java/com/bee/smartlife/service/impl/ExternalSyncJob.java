package com.bee.smartlife.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalSyncJob {
    private final ShopCacheSync shops;
    @Value("${smartlife.sync.enabled:true}") private boolean enabled;
    @Scheduled(initialDelayString="${smartlife.sync.delay-ms:5000}",fixedDelayString="${smartlife.sync.delay-ms:5000}")
    public void retry() {
        if(!enabled)return;
        try{shops.retryPending(100);}catch(RuntimeException unavailable){log.warn("外部同步等待恢复 error={}",unavailable.getClass().getSimpleName());}
    }
}
