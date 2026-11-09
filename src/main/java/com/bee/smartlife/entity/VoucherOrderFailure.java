package com.bee.smartlife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** Durable rejection and its Redis compensation outcome, separate from paid orders. */
@Data
@Accessors(chain = true)
@TableName("tb_voucher_order_failure")
public class VoucherOrderFailure {
    @TableId(value = "order_id", type = IdType.INPUT)
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private String reason;
    private Long retainedOrderId;
    private String compensationStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
