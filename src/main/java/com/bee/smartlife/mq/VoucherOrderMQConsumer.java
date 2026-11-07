package com.bee.smartlife.mq;

import cn.hutool.json.JSONUtil;
import com.bee.smartlife.dto.VoucherOrderMessage;
import com.bee.smartlife.entity.VoucherOrder;
import com.bee.smartlife.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class VoucherOrderMQConsumer {

    @Value("${rocketmq.name-server}")
    private String nameServer;

    @Value("${rocketmq.seckill-topic}")
    private String topic;

    @Resource
    private IVoucherOrderService voucherOrderService;

    private DefaultMQPushConsumer consumer;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        try {
            consumer = new DefaultMQPushConsumer("voucher-order-consumer-group");
            consumer.setNamesrvAddr(nameServer);
            consumer.setVipChannelEnabled(false);
            consumer.subscribe(topic, "*");

            consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
                try {
                    for (MessageExt msg : msgs) {
                        String body = new String(msg.getBody(), StandardCharsets.UTF_8);
                        VoucherOrderMessage orderMessage = JSONUtil.toBean(body, VoucherOrderMessage.class);

                        VoucherOrder voucherOrder = new VoucherOrder();
                        voucherOrder.setId(orderMessage.getOrderId());
                        voucherOrder.setVoucherId(orderMessage.getVoucherId());
                        voucherOrder.setUserId(orderMessage.getUserId());

                        voucherOrderService.createVoucherOrder(voucherOrder);
                    }
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                } catch (Exception e) {
                    log.error("消费秒杀订单消息失败", e);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            });

            consumer.start();
            log.info("RocketMQ消费者启动成功");
        } catch (Exception e) {
            log.error("RocketMQ消费者启动失败，不阻塞应用启动", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}
