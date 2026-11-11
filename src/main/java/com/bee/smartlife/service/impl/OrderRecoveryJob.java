package com.bee.smartlife.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class OrderRecoveryJob implements ApplicationRunner {
    private final OrderRecoveryService recovery;
    private final ObjectMapper json;
    @Value("${smartlife.orders.recovery-enabled:true}") private boolean enabled;
    @Value("${smartlife.orders.recovery-cli:false}") private boolean cli;
    @Value("${smartlife.orders.recovery-age-seconds:120}") private long age;
    @Value("${smartlife.orders.recovery-batch:100}") private int batch;

    @Scheduled(initialDelayString = "${smartlife.orders.recovery-delay-ms:60000}",
            fixedDelayString = "${smartlife.orders.recovery-delay-ms:60000}")
    public void recoverStaleOrders() {
        if (!enabled || cli) return;
        try {
            for (var result : recovery.execute(null, batch, age)) {
                log.info("订单恢复 orderId={} action={} outcome={}", result.orderId(), result.action(), result.outcome());
            }
        } catch (Exception unavailable) {
            log.warn("订单恢复批次暂不可用 error={}", unavailable.getClass().getSimpleName());
        }
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!cli) return;
        Long id = args.containsOption("recover.order-id")
                ? Long.valueOf(args.getOptionValues("recover.order-id").getFirst()) : null;
        boolean execute = args.containsOption("recover.execute")
                && Boolean.parseBoolean(args.getOptionValues("recover.execute").getFirst());
        var plan = recovery.preview(id, batch, age);
        System.out.println("ORDER_RECOVERY_PREVIEW=" + json.writeValueAsString(plan));
        if (execute) {
            var results = recovery.executePlan(plan, age);
            System.out.println("ORDER_RECOVERY_RESULT=" + json.writeValueAsString(results));
            if (results.stream().anyMatch(result -> "FAILED".equals(result.outcome()) || "RETRY".equals(result.outcome()))) {
                throw new IllegalStateException("部分恢复尚未确认，请检查审计记录并稍后重试");
            }
        }
    }
}
