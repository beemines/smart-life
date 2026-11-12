package com.bee.smartlife.dto;

public record OrderView(
    String id,
    String voucherId,
    String shopId,
    String shopName,
    String title,
    Long payValue,
    Long actualValue,
    int status,
    String createTime,
    String payTime,
    String useTime,
    String refundTime,
    String payDeadline,
    String useBeginTime,
    String useEndTime,
    String closeReason,
    String stockReturnState,
    boolean canPay,
    boolean canCancel,
    boolean canRefund,
    boolean canRedeem) {}
