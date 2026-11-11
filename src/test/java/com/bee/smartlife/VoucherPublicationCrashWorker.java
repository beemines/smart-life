package com.bee.smartlife;

import com.bee.smartlife.dto.UserDTO;
import com.bee.smartlife.entity.Voucher;
import com.bee.smartlife.service.IVoucherService;
import com.bee.smartlife.service.impl.VoucherStockPublisher;
import com.bee.smartlife.utils.UserHolder;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDateTime;

/** Test-only actual process exits; hooks never enter the packaged application. */
public class VoucherPublicationCrashWorker {
    private static String phase;
    public static void main(String[] args) throws Exception {
        phase=args[0];System.setProperty("spring.devtools.restart.enabled","false");
        boolean recovering=phase.equals("RECOVER");
        try(var context=new SpringApplication(SmartLifeApplication.class,Hooks.class).run(
                "--spring.main.web-application-type=none","--spring.main.banner-mode=off",
                "--smartlife.orders.consumer-enabled=false","--smartlife.orders.recovery-enabled=false",
                "--smartlife.cache.broadcast-enabled=false","--smartlife.sync.enabled="+recovering,
                "--smartlife.sync.delay-ms=200","--logging.level.root=WARN")) {
            if(recovering) {
                var jdbc=context.getBean(JdbcTemplate.class);long until=System.nanoTime()+90_000_000_000L;
                while(System.nanoTime()<until) {
                    String state=jdbc.queryForObject("SELECT p.state FROM tb_voucher_publication p JOIN tb_voucher v ON v.id=p.voucher_id WHERE v.title=?",String.class,args[3]);
                    if("READY".equals(state)){System.out.println("PUBLICATION_RECOVERED");return;}
                    Thread.sleep(200);
                }
                throw new IllegalStateException("publication recovery timed out");
            }
            var user=new UserDTO();user.setId(Long.parseLong(args[1]));UserHolder.saveUser(user);
            var voucher=new Voucher();voucher.setShopId(Long.parseLong(args[2]));voucher.setTitle(args[3]);voucher.setType(1);voucher.setStatus(1);
            voucher.setPayValue(100L);voucher.setActualValue(200L);voucher.setStock(1);
            voucher.setBeginTime(LocalDateTime.of(2000,1,1,0,0));voucher.setEndTime(LocalDateTime.of(2100,1,1,0,0));
            context.getBean(IVoucherService.class).addSeckillVoucher(voucher);
            throw new IllegalStateException("expected crash hook did not run");
        }
    }
    @TestConfiguration(proxyBeanMethods=false)
    public static class Hooks {
        @Bean static BeanPostProcessor publicationCrashBoundary() {
            return new BeanPostProcessor(){
                @Override public Object postProcessAfterInitialization(Object bean,String name) {
                    if(!(bean instanceof VoucherStockPublisher)||phase.equals("RECOVER"))return bean;
                    var proxy=new ProxyFactory(bean);proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor)call->{
                        if(!call.getMethod().getName().equals("publish"))return call.proceed();
                        if(phase.equals("AFTER_REDIS"))call.proceed();
                        System.out.println("PUBLICATION_CRASH="+phase);System.out.flush();Runtime.getRuntime().halt(74);return null;
                    });return proxy.getProxy();
                }
            };
        }
    }
}
