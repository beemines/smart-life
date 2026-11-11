package com.bee.smartlife.mq;

import com.bee.smartlife.service.impl.ShopCache;
import com.bee.smartlife.service.impl.ShopCacheSync;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.remoting.protocol.heartbeat.MessageModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShopCacheBroadcast {
    private final ShopCache cache;
    private final ObjectMapper json;
    @Value("${rocketmq.name-server}") private String nameserver;
    @Value("${rocketmq.shop-cache-topic:smartlife-shop-cache-change}") private String topic;
    @Value("${smartlife.cache.broadcast-enabled:true}") private boolean enabled;
    private DefaultMQPushConsumer consumer;
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if(!enabled||consumer!=null)return;
        var next=new DefaultMQPushConsumer("smartlife-shop-cache-broadcast");
        next.setInstanceName("shop-cache-"+UUID.randomUUID());next.setNamesrvAddr(nameserver);next.setVipChannelEnabled(false);
        next.setMessageModel(MessageModel.BROADCASTING);
        try {
            next.subscribe(topic,"*");
            next.registerMessageListener((MessageListenerConcurrently)(messages,context)->{
                for(var message:messages)try{
                    var change=json.readValue(message.getBody(),ShopCacheSync.Change.class);
                    if(change.shopId()>0&&change.version()>0)cache.evictLocal(change.shopId());
                }catch(Exception malformed){log.warn("忽略非法店铺缓存事件 messageId={}",message.getMsgId());}
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            next.start();consumer=next;
        }catch(Exception unavailable){next.shutdown();log.warn("店铺缓存广播暂不可用，TTL兜底 error={}",unavailable.getClass().getSimpleName());}
    }
    @PreDestroy public synchronized void shutdown(){if(consumer!=null){consumer.shutdown();consumer=null;}}
}
