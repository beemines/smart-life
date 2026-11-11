package com.bee.smartlife.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("tb_voucher_order_request")
public class VoucherOrderRequest {
    @TableId(value = "order_id", type = IdType.INPUT)
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private String requestKey;
    private String state;
    private String reason;
    private Integer attempts;
    private String lastError;
    private LocalDateTime lastAttemptAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public VoucherOrder toOrder() {
        return new VoucherOrder().setId(orderId).setUserId(userId).setVoucherId(voucherId);
    }
}
