package com.bee.smartlife.mq;

import cn.hutool.json.JSONUtil;
import com.bee.smartlife.dto.VoucherOrderMessage;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;

@Component
public class VoucherOrderMQProducer {

    @Resource
    private DefaultMQProducer defaultMQProducer;

    @Value("${rocketmq.seckill-topic}")
    private String topic;

    public SendResult sendSeckillOrder(VoucherOrderMessage orderMessage) throws Exception {
        String body = JSONUtil.toJsonStr(orderMessage);
        Message message = new Message(topic, body.getBytes(StandardCharsets.UTF_8));
        return defaultMQProducer.send(message);
    }
}
