package com.bee.smartlife.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class VoucherOrderMessage implements Serializable {
    private Long orderId;
    private Long voucherId;
    private Long userId;
}
