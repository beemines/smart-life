package com.bee.smartlife.dto;

/** IDs are strings at the browser boundary; payment state remains on VoucherOrder. */
public record OrderRequestStatus(String orderId, String requestId, String state,
                                 String message, String compensationStatus) {
}
