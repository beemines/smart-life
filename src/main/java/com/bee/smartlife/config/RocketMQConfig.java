package com.bee.smartlife.config;

import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RocketMQConfig {

    @Value("${rocketmq.name-server}")
    private String nameServer;

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQProducer voucherOrderProducer() throws MQClientException {
        DefaultMQProducer producer = new DefaultMQProducer("voucher-order-producer-group");
        producer.setNamesrvAddr(nameServer);
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(3000);
        producer.setRetryTimesWhenSendFailed(2);
        return producer;
    }
}
