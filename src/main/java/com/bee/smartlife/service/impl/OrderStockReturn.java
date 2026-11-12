package com.bee.smartlife.service.impl;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderStockReturn {
  private final StringRedisTemplate redis;
  private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();

  static {
    SCRIPT.setLocation(new ClassPathResource("order_stock_return.lua"));
    SCRIPT.setResultType(Long.class);
  }

  public long apply(long order, long voucher, long user) {
    var result =
        redis.execute(
            SCRIPT,
            List.of(
                "seckill:stock:" + voucher,
                "seckill:order:" + voucher,
                "seckill:reservation:" + voucher,
                "seckill:returned:" + order),
            Long.toString(voucher),
            Long.toString(user),
            Long.toString(order));
    if (result == null) throw new IllegalStateException("Redis return missing");
    return result;
  }
}
