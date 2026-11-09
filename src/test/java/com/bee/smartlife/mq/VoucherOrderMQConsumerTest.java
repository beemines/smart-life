package com.bee.smartlife.mq;

import com.bee.smartlife.dto.OrderProcessingResult;
import com.bee.smartlife.entity.VoucherOrder;
import com.bee.smartlife.service.IVoucherOrderService;
import com.bee.smartlife.service.impl.OrderRetryException;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VoucherOrderMQConsumerTest {
    private VoucherOrderMQConsumer consumer;
    private IVoucherOrderService orders;

    @BeforeEach
    void setUp() {
        consumer = new VoucherOrderMQConsumer();
        orders = mock(IVoucherOrderService.class);
        ReflectionTestUtils.setField(consumer, "voucherOrderService", orders);
    }

    @Test
    void deliveredOrderIdentityIsPreservedInsteadOfGeneratingANewOrder() {
        when(orders.createVoucherOrder(any())).thenReturn(OrderProcessingResult.CREATED);
        assertThat(consumer.consumeMessage(List.of(message("{\"orderId\":101,\"userId\":202,\"voucherId\":303}")), null))
                .isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        var captured = ArgumentCaptor.forClass(VoucherOrder.class);
        verify(orders).createVoucherOrder(captured.capture());
        assertThat(captured.getValue().getId()).isEqualTo(101L);
        assertThat(captured.getValue().getUserId()).isEqualTo(202L);
        assertThat(captured.getValue().getVoucherId()).isEqualTo(303L);
    }

    @Test
    void lockContentionRequestsAnotherDeliveryInsteadOfAcknowledging() {
        when(orders.createVoucherOrder(any())).thenThrow(new OrderRetryException("fixture lock busy"));
        assertThat(consumer.consumeMessage(List.of(message("{\"orderId\":101,\"userId\":202,\"voucherId\":303}")), null))
                .isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
    }

    @Test
    void missingProcessingResultMustNotBeAcknowledged() {
        when(orders.createVoucherOrder(any())).thenReturn(null);
        assertThat(consumer.consumeMessage(List.of(message("{\"orderId\":101,\"userId\":202,\"voucherId\":303}")), null))
                .isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
    }

    @Test
    void malformedPayloadIsRetriedWithoutCallingOrderPersistence() {
        assertThat(consumer.consumeMessage(List.of(message("not-json")), null))
                .isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        verifyNoInteractions(orders);
    }

    private MessageExt message(String body) {
        var message = new MessageExt();
        message.setMsgId("unit-fixture");
        message.setBody(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
