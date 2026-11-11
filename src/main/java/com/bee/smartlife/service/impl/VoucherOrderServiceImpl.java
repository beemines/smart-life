package com.bee.smartlife.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bee.smartlife.dto.*;
import com.bee.smartlife.entity.*;
import com.bee.smartlife.mapper.*;
import com.bee.smartlife.mq.VoucherOrderMQProducer;
import com.bee.smartlife.service.IVoucherOrderService;
import com.bee.smartlife.utils.RedisIdWorker;
import com.bee.smartlife.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendStatus;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource private RedisIdWorker redisIdWorker;
    @Resource private RedissonClient redissonClient;
    @Resource private VoucherOrderMQProducer voucherOrderMQProducer;
    @Resource private VoucherOrderPersistence persistence;
    @Resource private VoucherOrderReservation reservation;
    @Resource private VoucherOrderFailureMapper failures;
    @Resource private VoucherOrderRequestMapper requests;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result seckillVoucher(Long voucherId) {
        return seckillVoucher(voucherId, UUID.randomUUID().toString());
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result seckillVoucher(Long voucherId, String requestKey) {
        String key = requestKey == null ? UUID.randomUUID().toString() : requestKey;
        if (!key.matches("[A-Za-z0-9_-]{16,64}")) return Result.fail("请求标识格式不正确");
        long userId = UserHolder.getUser().getId();
        var proposed = new VoucherOrder().setId(redisIdWorker.nextId("order")).setUserId(userId).setVoucherId(voucherId);
        VoucherOrderPersistence.validate(proposed);
        VoucherOrderRequest request;
        try {
            // Commit intent BEFORE Redis. The owner/key uniqueness handles racing HTTP retries.
            request = persistence.accept(proposed, key);
        } catch (DuplicateKeyException raced) {
            request = persistence.requestByKey(userId, key);
            if (request == null) throw raced;
        } catch (IllegalArgumentException reused) {
            return Result.fail("同一请求标识不能用于其他优惠券");
        }
        if (!Objects.equals(request.getVoucherId(), voucherId)) return Result.fail("同一请求标识不能用于其他优惠券");
        VoucherOrder order = request.toOrder();
        try {
            String action = withOrderLocks(order, () -> prepareLocked(order, false));
            if ("SEND".equals(action)) publish(order);
        } catch (Exception deferred) {
            // Intent is durable. A timeout/lock failure is not proof that the order failed.
            log.warn("订单受理后等待恢复 orderId={} error={}", order.getId(), deferred.getClass().getSimpleName());
        }
        VoucherOrderRequest latest = persistence.findRequest(order.getId());
        if ("REJECTED".equals(latest.getState())) return Result.fail(reasonMessage(latest.getReason()));
        return Result.ok(order.getId().toString());
    }

    private String prepareLocked(VoucherOrder order, boolean resumePending) {
        OrderProcessingResult terminal = persistence.synchronize(order);
        if (terminal != null) {
            completeRejection(order, terminal);
            return terminal.isRejected() ? "REJECTED" : "CREATED";
        }
        VoucherOrderRequest request = persistence.findRequest(order.getId());
        if (request == null) throw new IllegalStateException("缺少受理记录");
        if ("PENDING".equals(request.getState()) && !resumePending) return "WAIT";
        long reserved = reservation.reserve(order, "PREPARING".equals(request.getState()));
        if (reserved != 0 && reserved != 4) {
            OrderProcessingResult rejected = persistence.rejectReservation(order,
                    "PENDING".equals(request.getState()) ? 5 : reserved);
            completeRejection(order, rejected);
            return "REJECTED";
        }
        // Resume recognizes this exact owner; never decrements the same reservation twice.
        persistence.markSending(order);
        return "SEND";
    }

    private boolean publish(VoucherOrder order) {
        var message = new VoucherOrderMessage();
        message.setOrderId(order.getId());
        message.setUserId(order.getUserId());
        message.setVoucherId(order.getVoucherId());
        String error = null;
        try {
            var sent = voucherOrderMQProducer.sendSeckillOrder(message);
            if (sent == null || sent.getSendStatus() != SendStatus.SEND_OK) error = "UNCONFIRMED_SEND";
        } catch (Exception uncertain) {
            error = uncertain.getClass().getSimpleName();
        }
        // This conditional write cannot turn a consumer's CREATED/REJECTED back into PENDING.
        persistence.recordSendOutcome(order.getId(), error);
        if (error != null) log.warn("订单发送待核对，保留预占 orderId={} error={}", order.getId(), error);
        return error == null;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OrderProcessingResult createVoucherOrder(VoucherOrder order) {
        VoucherOrderPersistence.validate(order);
        return withOrderLocks(order, () -> {
            OrderProcessingResult result = persistence.synchronize(order);
            if (result == null) {
                VoucherOrderRequest request = persistence.findRequest(order.getId());
                if (request != null && reservation.reserve(order, false) != 4) {
                    result = persistence.rejectReservation(order, 5);
                } else {
                    result = persistence.create(order);
                }
            }
            completeRejection(order, result);
            return result;
        });
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String recoverOrder(Long orderId, LocalDateTime staleBefore) {
        VoucherOrderRequest initial = persistence.findRequest(orderId);
        VoucherOrderFailure legacy = initial == null ? persistence.findFailure(orderId) : null;
        if (initial == null && legacy == null) return "NONE";
        VoucherOrder order = initial != null ? initial.toOrder() : new VoucherOrder().setId(orderId)
                .setUserId(legacy.getUserId()).setVoucherId(legacy.getVoucherId());
        String action = withOrderLocks(order, () -> {
            VoucherOrderRequest current = persistence.findRequest(orderId);
            VoucherOrderFailure failure = persistence.findFailure(orderId);
            LocalDateTime updated = current != null ? current.getUpdateTime() : failure.getUpdateTime();
            if (updated.isAfter(staleBefore)) return "WAIT";
            OrderProcessingResult terminal = persistence.synchronize(order);
            if (terminal != null) {
                completeRejection(order, terminal);
                if (!terminal.isRejected()) return "SYNC_CREATED";
                return "manual".equals(persistence.findFailure(orderId).getCompensationStatus()) ? "MANUAL" : "COMPENSATED";
            }
            return prepareLocked(order, true);
        });
        if ("SEND".equals(action)) {
            if (publish(order)) return "REQUEUED";
            return persistence.findOrder(orderId) != null ? "SYNC_CREATED" : "SEND_UNCONFIRMED";
        }
        return action;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Result queryOrderResult(Long orderId) {
        return project(orderId, UserHolder.getUser().getId());
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Result queryRequestResult(String key) {
        VoucherOrderRequest request = persistence.requestByKey(UserHolder.getUser().getId(), key);
        return request == null ? Result.fail("订单记录不存在") : project(request.getOrderId(), request.getUserId());
    }

    private Result project(Long orderId, Long userId) {
        VoucherOrderRequest request = requests.selectById(orderId);
        VoucherOrder existing = getById(orderId);
        VoucherOrderFailure failure = failures.selectById(orderId);
        if (request != null && !Objects.equals(request.getUserId(), userId)) return Result.fail("订单记录不存在");
        boolean owned = request != null || (existing != null && Objects.equals(existing.getUserId(), userId))
                || (failure != null && Objects.equals(failure.getUserId(), userId));
        if (!owned) return Result.fail("订单记录不存在");
        String state;
        String message;
        if ((existing != null && failure != null) || (existing != null && !Objects.equals(existing.getUserId(), userId))
                || (failure != null && !Objects.equals(failure.getUserId(), userId))) {
            state = "REVIEW"; message = "订单记录需要人工核对";
        } else if (existing != null) {
            state = "CREATED"; message = "订单创建成功";
        } else if (failure != null) {
            state = "manual".equals(failure.getCompensationStatus()) ? "REVIEW" : "REJECTED";
            message = "REVIEW".equals(state) ? "订单需要人工核对，请勿重复提交" : reasonMessage(failure.getReason());
        } else if (request != null && ("PREPARING".equals(request.getState()) || "PENDING".equals(request.getState()))) {
            state = request.getState(); message = "已受理，正在处理";
        } else {
            state = "REVIEW"; message = "订单结果需要核对";
        }
        return Result.ok(new OrderRequestStatus(orderId.toString(), request == null ? null : request.getRequestKey(),
                state, message, failure == null ? null : failure.getCompensationStatus()));
    }

    private <T> T withOrderLocks(VoucherOrder order, Supplier<T> action) {
        RLock orderLock = redissonClient.getLock("lock:order-id:" + order.getId());
        if (!orderLock.tryLock()) throw new OrderRetryException("订单ID锁竞争，稍后重试: " + order.getId());
        try {
            RLock userLock = redissonClient.getLock("lock:order:" + order.getUserId());
            if (!userLock.tryLock()) throw new OrderRetryException("用户订单锁竞争，稍后重试: " + order.getId());
            try { return action.get(); } finally { userLock.unlock(); }
        } finally { orderLock.unlock(); }
    }

    private void completeRejection(VoucherOrder order, OrderProcessingResult result) {
        if (!result.isRejected()) return;
        VoucherOrderFailure failure = persistence.findFailure(order.getId());
        if (failure == null) throw new IllegalStateException("缺少已提交的订单失败结果");
        if ("done".equals(failure.getCompensationStatus()) || "manual".equals(failure.getCompensationStatus())) return;
        if (!"pending".equals(failure.getCompensationStatus())) throw new IllegalStateException("无效的补偿状态");
        String status = reservation.compensate(failure);
        persistence.finishCompensation(order.getId(), status);
        if ("manual".equals(status)) log.error("订单补偿需要人工核对 orderId={} reason={}", order.getId(), failure.getReason());
    }

    private String reasonMessage(String reason) {
        if ("REJECTED_OUT_OF_STOCK".equals(reason)) return "库存不足";
        if ("REJECTED_ALREADY_PURCHASED".equals(reason)) return "不能重复下单";
        if ("REJECTED_SEND_FAILED".equals(reason)) return "下单失败";
        if ("REJECTED_VOUCHER_UNAVAILABLE".equals(reason)) return "优惠券不存在、未上架或尚未准备就绪";
        if ("REJECTED_NOT_STARTED".equals(reason)) return "优惠券抢购尚未开始";
        if ("REJECTED_ENDED".equals(reason)) return "优惠券抢购已经结束";
        return "订单需要人工核对";
    }
}
