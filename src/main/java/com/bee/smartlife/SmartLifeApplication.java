package com.bee.smartlife;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.bee.smartlife.mapper")
@SpringBootApplication
public class SmartLifeApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(SmartLifeApplication.class, args);
        if (context.getEnvironment().getProperty("smartlife.orders.recovery-cli", Boolean.class, false)) {
            context.close();
        }
    }

}
