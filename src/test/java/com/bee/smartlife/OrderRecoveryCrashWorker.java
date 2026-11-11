package com.bee.smartlife;

import com.bee.smartlife.dto.UserDTO;
import com.bee.smartlife.mq.VoucherOrderMQProducer;
import com.bee.smartlife.service.IVoucherOrderService;
import com.bee.smartlife.service.impl.VoucherOrderReservation;
import com.bee.smartlife.utils.UserHolder;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Test-only process-kill hooks around real production boundaries, never shipped in the JAR. */
public class OrderRecoveryCrashWorker {
    public static void main(String[] args) {
        System.setProperty("spring.devtools.restart.enabled", "false");
        System.setProperty("smartlife.test.crash-phase", args[0]);
        try (var context = new SpringApplication(SmartLifeApplication.class, CrashHooks.class).run(
                "--spring.main.web-application-type=none", "--spring.main.banner-mode=off",
                "--smartlife.orders.consumer-enabled=false", "--smartlife.orders.recovery-enabled=false",
                "--rocketmq.seckill-topic=" + args[4], "--logging.level.root=WARN")) {
            var user = new UserDTO();
            user.setId(Long.parseLong(args[1]));
            UserHolder.saveUser(user);
            context.getBean(IVoucherOrderService.class).seckillVoucher(Long.parseLong(args[2]), args[3]);
            throw new IllegalStateException("Expected process crash hook did not execute");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class CrashHooks {
        @Bean
        static BeanPostProcessor crashAtProductionBoundary() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    String phase = System.getProperty("smartlife.test.crash-phase");
                    boolean reserve = "RESERVE".equals(phase) && bean instanceof VoucherOrderReservation;
                    boolean send = "SEND".equals(phase) && bean instanceof VoucherOrderMQProducer;
                    if (!reserve && !send) return bean;
                    var proxy = new ProxyFactory(bean);
                    proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor) call -> {
                        Object result = call.proceed();
                        if ((reserve && call.getMethod().getName().equals("reserve"))
                                || (send && call.getMethod().getName().equals("sendSeckillOrder"))) {
                            System.out.println("CRASH_BOUNDARY=" + phase);
                            System.out.flush();
                            Runtime.getRuntime().halt(73);
                        }
                        return result;
                    });
                    return proxy.getProxy();
                }
            };
        }
    }
}
