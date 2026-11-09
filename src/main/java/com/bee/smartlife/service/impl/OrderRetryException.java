package com.bee.smartlife.service.impl;

/** The order was not processed; the consumer must request another delivery. */
public class OrderRetryException extends IllegalStateException {
    public OrderRetryException(String message) {
        super(message);
    }
}
