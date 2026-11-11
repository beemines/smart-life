package com.bee.smartlife.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
public class VoucherStockPublisher {
    private static final DefaultRedisScript<Long> SCRIPT=new DefaultRedisScript<>();
    static {SCRIPT.setLocation(new ClassPathResource("voucher_publish.lua"));SCRIPT.setResultType(Long.class);}
    private final StringRedisTemplate redis;
    public long publish(long id,boolean maySeed,int initialStock) {
        Long result=redis.execute(SCRIPT,List.of("seckill:stock:"+id,"seckill:order:"+id,"seckill:reservation:"+id),maySeed?"1":"0",Integer.toString(initialStock));
        if(result==null)throw new IllegalStateException("活动库存发布结果为空");return result;
    }
}
