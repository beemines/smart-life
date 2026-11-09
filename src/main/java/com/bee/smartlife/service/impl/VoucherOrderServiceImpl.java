package com.bee.smartlife.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bee.smartlife.dto.OrderProcessingResult;
import com.bee.smartlife.dto.Result;
import com.bee.smartlife.dto.VoucherOrderMessage;
import com.bee.smartlife.entity.VoucherOrder;
import com.bee.smartlife.entity.VoucherOrderFailure;
import com.bee.smartlife.mapper.VoucherOrderFailureMapper;
import com.bee.smartlife.mapper.VoucherOrderMapper;
import com.bee.smartlife.mq.VoucherOrderMQProducer;
import com.bee.smartlife.service.IVoucherOrderService;
import com.bee.smartlife.utils.RedisIdWorker;
import com.bee.smartlife.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendStatus;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.function.Supplier;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {
    @Resource private RedisIdWorker redisIdWorker;
    @Resource private RedissonClient redissonClient;
    @Resource private VoucherOrderMQProducer voucherOrderMQProducer;
    @Resource private VoucherOrderPersistence persistence;
    @Resource private VoucherOrderReservation reservation;
    @Resource private VoucherOrderFailureMapper failures;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result seckillVoucher(Long voucherId) {
        long orderId = redisIdWorker.nextId("order");
        var order = new VoucherOrder().setId(orderId).setUserId(UserHolder.getUser().getId()).setVoucherId(voucherId);
        VoucherOrderPersistence.validate(order);
        long reserved = reservation.reserve(order);
        if (reserved != 0) {
            return Result.fail(reserved == 1 ? "库存不足" : reserved == 2 ? "不能重复下单" : "库存数据异常，请稍后重试");
        }
        var message = new VoucherOrderMessage();
        message.setOrderId(orderId);
        message.setUserId(order.getUserId());
        message.setVoucherId(voucherId);
        try {
            var sent = voucherOrderMQProducer.sendSeckillOrder(message);
            if (sent == null || sent.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("Broker未确认消息持久化成功");
            }
            return Result.ok(orderId);
        } catch (Exception sendFailure) {
            log.warn("订单消息发送结果不确定 orderId={} userId={} voucherId={}", orderId, order.getUserId(), voucherId);
            try {
                OrderProcessingResult result = withOrderLocks(order, () -> {
                    // A committed success wins; fence a late delivery before releasing stock.
                    OrderProcessingResult outcome = persistence.rejectUnconfirmedSend(order);
                    completeRejection(order, outcome);
                    return outcome;
                });
                if (!result.isRejected()) return Result.ok(orderId);
                return Result.fail("下单失败，请重试");
            } catch (Exception unresolved) {
                // Do not guess whether an ambiguous send reached the broker.
                unresolved.addSuppressed(sendFailure);
                log.error("订单发送结果待核对 orderId={} userId={} voucherId={}", orderId, order.getUserId(), voucherId, unresolved);
                return Result.fail("下单结果待确认，订单号：" + orderId + "，请勿重复提交");
            }
        }
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OrderProcessingResult createVoucherOrder(VoucherOrder order) {
        VoucherOrderPersistence.validate(order);
        return withOrderLocks(order, () -> {
            // The separate Spring proxy returns only AFTER transaction commit/rollback.
            OrderProcessingResult result = persistence.create(order);
            completeRejection(order, result);
            return result;
        });
    }

    private OrderProcessingResult withOrderLocks(VoucherOrder order, Supplier<OrderProcessingResult> action) {
        // The ID lock also serializes corrupt payloads carrying the same ID but different users.
        // Always acquire ID -> user; never wait while holding the other lock.
        RLock orderLock = redissonClient.getLock("lock:order-id:" + order.getId());
        if (!orderLock.tryLock()) throw new OrderRetryException("订单ID锁竞争，稍后重试: " + order.getId());
        try {
            RLock userLock = redissonClient.getLock("lock:order:" + order.getUserId());
            if (!userLock.tryLock()) throw new OrderRetryException("用户订单锁竞争，稍后重试: " + order.getId());
            try {
                return action.get();
            } finally {
                userLock.unlock();
            }
        } finally {
            orderLock.unlock();
        }
    }

    private void completeRejection(VoucherOrder order, OrderProcessingResult result) {
        if (!result.isRejected()) return;
        VoucherOrderFailure failure = failures.selectById(order.getId());
        if (failure == null) throw new IllegalStateException("缺少已提交的订单失败结果");
        if ("done".equals(failure.getCompensationStatus()) || "manual".equals(failure.getCompensationStatus())) return;
        if (!"pending".equals(failure.getCompensationStatus())) throw new IllegalStateException("无效的补偿状态");
        // Replay after Redis success but before this UPDATE is safe: Lua checks ownership.
        String status = reservation.compensate(failure);
        int updated = failures.update(null, new UpdateWrapper<VoucherOrderFailure>()
                .eq("order_id", order.getId()).eq("compensation_status", "pending")
                .set("compensation_status", status));
        if (updated != 1) throw new OrderRetryException("订单补偿结果未保存: " + order.getId());
        if ("manual".equals(status)) {
            log.error("订单补偿需要人工核对 orderId={} userId={} voucherId={} reason={}",
                    order.getId(), order.getUserId(), order.getVoucherId(), failure.getReason());
        }
    }
}
