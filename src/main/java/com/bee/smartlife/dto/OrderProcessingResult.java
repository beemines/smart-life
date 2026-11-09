package com.bee.smartlife.dto;

public enum OrderProcessingResult {
    CREATED(false),
    ALREADY_PROCESSED(false),
    REJECTED_OUT_OF_STOCK(true),
    REJECTED_ALREADY_PURCHASED(true),
    REJECTED_SEND_FAILED(true);

    private final boolean rejected;

    OrderProcessingResult(boolean rejected) {
        this.rejected = rejected;
    }

    public boolean isRejected() {
        return rejected;
    }
}
