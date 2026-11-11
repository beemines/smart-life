package com.bee.smartlife.mq;

import cn.hutool.json.JSONUtil;
import com.bee.smartlife.dto.VoucherOrderMessage;
import com.bee.smartlife.entity.VoucherOrder;
import com.bee.smartlife.service.IVoucherOrderService;
import com.bee.smartlife.service.impl.OrderRetryException;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Component
public class VoucherOrderMQConsumer implements MessageListenerConcurrently {

    @Value("${rocketmq.name-server}")
    private String nameServer;

    @Value("${rocketmq.seckill-topic}")
    private String topic;

    @Value("${rocketmq.consumer-group:voucher-order-consumer-group}")
    private String consumerGroup;

    @Resource
    private IVoucherOrderService voucherOrderService;

    private DefaultMQPushConsumer consumer;

    @Value("${smartlife.orders.consumer-enabled:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) return;
        try {
            consumer = new DefaultMQPushConsumer(consumerGroup);
            consumer.setNamesrvAddr(nameServer);
            consumer.setVipChannelEnabled(false);
            consumer.subscribe(topic, "*");
            consumer.setMaxReconsumeTimes(16);
            consumer.registerMessageListener(this);

            consumer.start();
            log.info("RocketMQ消费者启动成功");
        } catch (Exception e) {
            log.error("RocketMQ消费者启动失败，不阻塞应用启动", e);
        }
    }

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
        for (MessageExt msg : msgs) {
            Long orderId = null;
            try {
                String body = new String(msg.getBody(), StandardCharsets.UTF_8);
                VoucherOrderMessage incoming = JSONUtil.toBean(body, VoucherOrderMessage.class);
                orderId = incoming.getOrderId();
                var order = new VoucherOrder().setId(orderId)
                        .setUserId(incoming.getUserId()).setVoucherId(incoming.getVoucherId());
                var result = voucherOrderService.createVoucherOrder(order);
                if (result == null) throw new IllegalStateException("订单处理结果为空");
                log.info("订单消息处理完成 orderId={} messageId={} delivery={} result={}",
                        orderId, msg.getMsgId(), msg.getReconsumeTimes(), result);
            } catch (OrderRetryException retry) {
                log.warn("订单消息稍后重试 orderId={} messageId={} delivery={} reason={}",
                        orderId, msg.getMsgId(), msg.getReconsumeTimes(), retry.getMessage());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            } catch (Exception failure) {
                log.error("订单消息处理失败 orderId={} messageId={} delivery={}",
                        orderId, msg.getMsgId(), msg.getReconsumeTimes(), failure);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    @PreDestroy
    public void shutdown() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}
