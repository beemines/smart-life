package com.bee.smartlife.mq;

import com.bee.smartlife.service.impl.OrderLifecyclePersistence;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.client.consumer.*;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.message.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@lombok.extern.slf4j.Slf4j
@Component
public class OrderCloseMQ {
  @Resource private DefaultMQProducer producer;
  @Resource private OrderLifecyclePersistence persistence;

  @Value("${rocketmq.seckill-topic}")
  private String topic;

  @Value("${rocketmq.name-server}")
  private String nameserver;

  @Value("${rocketmq.consumer-group:voucher-order-consumer-group}")
  private String group;

  @Value("${smartlife.orders.consumer-enabled:true}")
  private boolean enabled;

  private DefaultMQPushConsumer consumer;

  public void send(long id) throws Exception {
    var message =
        new Message(topic + "-close", Long.toString(id).getBytes(StandardCharsets.US_ASCII));
    var row = persistence.read(id);
    long seconds =
        Math.max(
            1,
            (java.time.Duration.between(
                            persistence.now(), OrderLifecyclePersistence.time(row, "pay_deadline"))
                        .toMillis()
                    + 999)
                / 1000);
    long[] levels = {
      1, 5, 10, 30, 60, 120, 180, 240, 300, 360, 420, 480, 540, 600, 1200, 1800, 3600, 7200
    };
    int level = levels.length;
    for (int i = 0; i < levels.length; i++) {
      if (seconds <= levels[i]) {
        level = i + 1;
        break;
      }
    }
    message.setDelayTimeLevel(level);
    var result = producer.send(message);
    if (result == null || result.getSendStatus() != SendStatus.SEND_OK)
      throw new IllegalStateException("unconfirmed close send");
  }

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    if (!enabled) return;
    try {
      consumer = new DefaultMQPushConsumer(group + "-close");
      consumer.setNamesrvAddr(nameserver);
      consumer.setVipChannelEnabled(false);
      consumer.subscribe(topic + "-close", "*");
      consumer.registerMessageListener(
          (MessageListenerConcurrently)
              (messages, context) -> {
                for (var msg : messages)
                  try {
                    persistence.action(
                        Long.parseLong(new String(msg.getBody(), StandardCharsets.US_ASCII)),
                        "timeout",
                        true);
                  } catch (Exception e) {
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                  }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
              });
      consumer.start();
    } catch (Exception e) {
      log.warn(
          "Close consumer unavailable; SQL recovery remains active reason={}",
          e.getClass().getSimpleName());
    }
  }

  @PreDestroy
  public void stop() {
    if (consumer != null) consumer.shutdown();
  }
}
