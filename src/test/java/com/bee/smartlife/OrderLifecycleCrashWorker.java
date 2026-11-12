package com.bee.smartlife;

import com.bee.smartlife.service.impl.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

public class OrderLifecycleCrashWorker {
  public static void main(String[] args) {
    System.setProperty("spring.devtools.restart.enabled", "false");
    try (var context =
        new SpringApplicationBuilder(SmartLifeApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                "--smartlife.orders.consumer-enabled=false",
                "--smartlife.orders.lifecycle-enabled=false",
                "--smartlife.orders.recovery-enabled=false",
                "--smartlife.sync.enabled=false")) {
      if (args[0].equals("APPLY")) {
        long result =
            context
                .getBean(OrderStockReturn.class)
                .apply(Long.parseLong(args[1]), Long.parseLong(args[2]), Long.parseLong(args[3]));
        if (result > 1) throw new IllegalStateException("return proof missing");
        Runtime.getRuntime().halt(74);
      }
      context.getBean(OrderLifecycleService.class).recover();
    }
  }
}
