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
        SpringApplication.run(SmartLifeApplication.class, args);
    }

}
