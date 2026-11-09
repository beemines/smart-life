package com.bee.smartlife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bee.smartlife.dto.OrderProcessingResult;
import com.bee.smartlife.entity.VoucherOrder;
import com.bee.smartlife.entity.VoucherOrderFailure;
import com.bee.smartlife.mapper.VoucherOrderFailureMapper;
import com.bee.smartlife.mapper.VoucherOrderMapper;
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
        VoucherOrder existing = orders.selectById(incoming.getId());
        VoucherOrderFailure failure = failures.selectById(incoming.getId());
        if (existing != null && failure != null) {
            throw new IllegalStateException("同一订单同时存在成功和失败记录，需要核对: " + incoming.getId());
        }
        if (existing != null) {
            requireSamePayload(incoming, existing.getUserId(), existing.getVoucherId());
            return OrderProcessingResult.ALREADY_PROCESSED;
        }
        if (failure == null) return null;
        requireSamePayload(incoming, failure.getUserId(), failure.getVoucherId());
        OrderProcessingResult result = OrderProcessingResult.valueOf(failure.getReason());
        if (!result.isRejected()) throw new IllegalStateException("无效的订单失败记录");
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
        return reason;
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
