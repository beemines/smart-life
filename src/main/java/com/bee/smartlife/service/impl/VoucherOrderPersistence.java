package com.bee.smartlife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.bee.smartlife.dto.OrderProcessingResult;
import com.bee.smartlife.entity.VoucherOrder;
import com.bee.smartlife.entity.VoucherOrderFailure;
import com.bee.smartlife.entity.VoucherOrderRequest;
import com.bee.smartlife.mapper.VoucherOrderFailureMapper;
import com.bee.smartlife.mapper.VoucherOrderMapper;
import com.bee.smartlife.mapper.VoucherOrderRequestMapper;
import com.bee.smartlife.service.ISeckillVoucherService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Called through its Spring proxy while the outer service holds the user lock. */
@Service
@RequiredArgsConstructor
public class VoucherOrderPersistence {
    private final VoucherOrderMapper orders;
    private final VoucherOrderFailureMapper failures;
    private final ISeckillVoucherService vouchers;
    private final VoucherOrderRequestMapper requests;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VoucherOrderRequest accept(VoucherOrder order, String key) {
        validate(order);
        VoucherOrderRequest existing = requestByKey(order.getUserId(), key);
        if (existing != null) {
            requireSamePayload(order, existing.getUserId(), existing.getVoucherId());
            return existing;
        }
        var request = new VoucherOrderRequest().setOrderId(order.getId()).setUserId(order.getUserId())
                .setVoucherId(order.getVoucherId()).setRequestKey(key).setState("PREPARING").setAttempts(0);
        if (requests.insert(request) != 1) throw new IllegalStateException("受理记录保存失败");
        return requests.selectById(order.getId());
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public VoucherOrderRequest requestByKey(Long userId, String key) {
        return requests.selectOne(new QueryWrapper<VoucherOrderRequest>()
                .eq("user_id", userId).eq("request_key", key));
    }

    // Orchestration suspends transactions. Fresh, bounded read transactions avoid
    // reusing a MyBatis first-level cache after an inner transaction commits.
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public VoucherOrderRequest findRequest(Long id) { return requests.selectById(id); }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public VoucherOrderFailure findFailure(Long id) { return failures.selectById(id); }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public VoucherOrder findOrder(Long id) { return orders.selectById(id); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderProcessingResult synchronize(VoucherOrder order) {
        return terminalResult(order);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSending(VoucherOrder order) {
        int changed = requests.update(null, new UpdateWrapper<VoucherOrderRequest>()
                .eq("order_id", order.getId()).in("state", "PREPARING", "PENDING")
                .set("state", "PENDING").setSql("last_attempt_at=CURRENT_TIMESTAMP")
                .set("last_error", null).setSql("attempts=attempts+1"));
        if (changed != 1) throw new IllegalStateException("受理记录已变更，不能发送");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSendOutcome(Long orderId, String error) {
        requests.update(null, new UpdateWrapper<VoucherOrderRequest>()
                .eq("order_id", orderId).eq("state", "PENDING").set("last_error", error));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderProcessingResult rejectReservation(VoucherOrder order, long result) {
        OrderProcessingResult terminal = terminalResult(order);
        if (terminal != null) return terminal;
        VoucherOrder existing = findUserVoucher(order);
        OrderProcessingResult reason = result == 1 ? OrderProcessingResult.REJECTED_OUT_OF_STOCK
                : result == 2 ? OrderProcessingResult.REJECTED_ALREADY_PURCHASED
                : result == 6 ? OrderProcessingResult.REJECTED_VOUCHER_UNAVAILABLE
                : result == 7 ? OrderProcessingResult.REJECTED_NOT_STARTED
                : result == 8 ? OrderProcessingResult.REJECTED_ENDED
                : OrderProcessingResult.REJECTED_RESERVATION_INVALID;
        OrderProcessingResult rejected = reject(order, reason, existing == null ? null : existing.getId());
        if (reason == OrderProcessingResult.REJECTED_RESERVATION_INVALID) {
            failures.update(null, new UpdateWrapper<VoucherOrderFailure>().eq("order_id", order.getId())
                    .set("compensation_status", "manual"));
            updateRequest(order.getId(), "REVIEW", reason.name());
        }
        return rejected;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishCompensation(Long orderId, String status) {
        int changed = failures.update(null, new UpdateWrapper<VoucherOrderFailure>()
                .eq("order_id", orderId).eq("compensation_status", "pending").set("compensation_status", status));
        if (changed != 1) throw new OrderRetryException("订单补偿结果未保存: " + orderId);
        VoucherOrderFailure failure = failures.selectById(orderId);
        updateRequest(orderId, "manual".equals(status) ? "REVIEW" : "REJECTED", failure.getReason());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderProcessingResult create(VoucherOrder order) {
        validate(order);
        OrderProcessingResult terminal = terminalResult(order);
        if (terminal != null) return terminal;

        VoucherOrder existing = findUserVoucher(order);
        if (existing != null) {
            return reject(order, OrderProcessingResult.REJECTED_ALREADY_PURCHASED, existing.getId());
        }
        boolean deducted = vouchers.update().setSql("stock = stock - 1")
                .eq("voucher_id", order.getVoucherId()).gt("stock", 0).update();
        if (!deducted) return reject(order, OrderProcessingResult.REJECTED_OUT_OF_STOCK, null);

        // Do not swallow insert/unique-key failures: this deduction must roll back.
        if (orders.insert(order) != 1) throw new IllegalStateException("订单保存失败");
        updateRequest(order.getId(), "CREATED", null);
        return OrderProcessingResult.CREATED;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderProcessingResult rejectUnconfirmedSend(VoucherOrder order) {
        validate(order);
        OrderProcessingResult terminal = terminalResult(order);
        if (terminal != null) return terminal;
        VoucherOrder existing = findUserVoucher(order);
        // Fence a late delivery BEFORE releasing this order's reservation.
        return reject(order, OrderProcessingResult.REJECTED_SEND_FAILED,
                existing == null ? null : existing.getId());
    }

    private OrderProcessingResult terminalResult(VoucherOrder incoming) {
        VoucherOrderRequest request = requests.selectById(incoming.getId());
        if (request != null) requireSamePayload(incoming, request.getUserId(), request.getVoucherId());
        VoucherOrder existing = orders.selectById(incoming.getId());
        VoucherOrderFailure failure = failures.selectById(incoming.getId());
        if (existing != null && failure != null) {
            throw new IllegalStateException("同一订单同时存在成功和失败记录，需要核对: " + incoming.getId());
        }
        if (existing != null) {
            requireSamePayload(incoming, existing.getUserId(), existing.getVoucherId());
            updateRequest(incoming.getId(), "CREATED", null);
            return OrderProcessingResult.ALREADY_PROCESSED;
        }
        if (failure == null) {
            if (request != null && !"PREPARING".equals(request.getState()) && !"PENDING".equals(request.getState())) {
                throw new IllegalStateException("受理终态缺少对应订单或失败记录");
            }
            return null;
        }
        requireSamePayload(incoming, failure.getUserId(), failure.getVoucherId());
        OrderProcessingResult result = OrderProcessingResult.valueOf(failure.getReason());
        if (!result.isRejected()) throw new IllegalStateException("无效的订单失败记录");
        updateRequest(incoming.getId(), "manual".equals(failure.getCompensationStatus()) ? "REVIEW" : "REJECTED", failure.getReason());
        return result;
    }

    private VoucherOrder findUserVoucher(VoucherOrder order) {
        return orders.selectOne(new QueryWrapper<VoucherOrder>()
                .eq("user_id", order.getUserId()).eq("voucher_id", order.getVoucherId()));
    }

    private OrderProcessingResult reject(VoucherOrder order, OrderProcessingResult reason, Long retainedId) {
        var failure = new VoucherOrderFailure().setOrderId(order.getId())
                .setUserId(order.getUserId()).setVoucherId(order.getVoucherId())
                .setReason(reason.name()).setRetainedOrderId(retainedId).setCompensationStatus("pending");
        if (failures.insert(failure) != 1) throw new IllegalStateException("订单失败结果保存失败");
        updateRequest(order.getId(), "REJECTED", reason.name());
        return reason;
    }

    private void updateRequest(Long orderId, String state, String reason) {
        requests.update(null, new UpdateWrapper<VoucherOrderRequest>().eq("order_id", orderId)
                .set("state", state).set("reason", reason));
    }

    static void validate(VoucherOrder order) {
        if (order == null || order.getId() == null || order.getId() <= 0
                || order.getUserId() == null || order.getUserId() <= 0
                || order.getVoucherId() == null || order.getVoucherId() <= 0) {
            throw new IllegalArgumentException("订单ID、用户ID和券ID必须为正整数");
        }
    }

    private void requireSamePayload(VoucherOrder incoming, Long userId, Long voucherId) {
        if (!Objects.equals(incoming.getUserId(), userId)
                || !Objects.equals(incoming.getVoucherId(), voucherId)) {
            throw new IllegalArgumentException("订单ID对应的用户或优惠券不一致: " + incoming.getId());
        }
    }
}
