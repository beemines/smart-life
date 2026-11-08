package com.bee.smartlife.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;
    private static final long MAX_SEQUENCE = 0xFFFF_FFFFL;
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy:MM:dd").withZone(ZoneOffset.UTC);

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    public long nextId(String keyPrefix) {
        return nextId(keyPrefix, Clock.systemUTC());
    }

    // 测试可传固定时钟，时间戳与日期桶始终取自同一个时刻。
    long nextId(String keyPrefix, Clock clock) {
        Instant now = clock.instant();
        long timestamp = now.getEpochSecond() - BEGIN_TIMESTAMP;
        // 正 long：1 位符号位、31 位时间差、32 位序列；越界时拒绝发号。
        if (timestamp < 0 || timestamp > Integer.MAX_VALUE) {
            throw new IllegalStateException("时间差超出订单 ID 编码范围");
        }

        String date = DATE_FORMAT.format(now);
        Long sequence = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        if (sequence == null || sequence <= 0 || sequence > MAX_SEQUENCE) {
            throw new IllegalStateException("订单 ID 序列不可用或超出 32 位范围");
        }
        // Redis 计数必须保留，不能在失败时改用各实例独立的本地计数。
        return (timestamp << COUNT_BITS) | sequence;
    }

}
