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
    private final VoucherEligibility eligibility;
    private final VoucherPublication publication;

    public long reserve(VoucherOrder order) {
        return reserve(order, true);
    }

    public long reserve(VoucherOrder order, boolean allowNewReservation) {
        if(!allowNewReservation)return evaluate(order,false,new VoucherEligibility.Rule(false,0,0));
        var lock=publication.lock(order.getVoucherId());boolean held=false;
        try {
            held=lock.tryLock(3,java.util.concurrent.TimeUnit.SECONDS);
            if(!held)throw new OrderRetryException("活动正在更新，稍后重试");
            return evaluate(order,true,eligibility.rule(order.getVoucherId()));
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new OrderRetryException("活动预占被中断");}
        finally{if(held)lock.unlock();}
    }

    private long evaluate(VoucherOrder order,boolean allowNewReservation,VoucherEligibility.Rule rule) {
        Long result = redis.execute(RESERVE, keys(order.getVoucherId()),
                order.getVoucherId().toString(), order.getUserId().toString(), order.getId().toString(),
                allowNewReservation ? "1" : "0",rule.available()?"1":"0",Long.toString(rule.beginSecond()),Long.toString(rule.endSecond()));
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
