package com.bee.smartlife.service.impl;

import com.bee.smartlife.entity.VoucherOrder;
import com.bee.smartlife.entity.VoucherOrderFailure;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
public class VoucherOrderReservation {
    private static final DefaultRedisScript<Long> RESERVE = script("seckill.lua");
    private static final DefaultRedisScript<Long> COMPENSATE = script("seckill_rollback.lua");
    private final StringRedisTemplate redis;

    public long reserve(VoucherOrder order) {
        Long result = redis.execute(RESERVE, keys(order.getVoucherId()),
                order.getVoucherId().toString(), order.getUserId().toString(), order.getId().toString());
        if (result == null) throw new OrderRetryException("Redis预占结果为空");
        return result;
    }

    public String compensate(VoucherOrderFailure failure) {
        Long result = redis.execute(COMPENSATE, keys(failure.getVoucherId()),
                failure.getVoucherId().toString(), failure.getUserId().toString(), failure.getOrderId().toString(),
                failure.getRetainedOrderId() == null ? "" : failure.getRetainedOrderId().toString());
        if (result == null || result < 0 || result > 2) throw new OrderRetryException("Redis补偿结果无效");
        return result == 2 ? "manual" : "done";
    }

    private List<String> keys(Long voucherId) {
        return List.of("seckill:stock:" + voucherId, "seckill:order:" + voucherId,
                "seckill:reservation:" + voucherId);
    }

    private static DefaultRedisScript<Long> script(String resource) {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource(resource));
        script.setResultType(Long.class);
        return script;
    }
}
