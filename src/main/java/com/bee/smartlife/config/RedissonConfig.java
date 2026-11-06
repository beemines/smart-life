package com.bee.smartlife.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public Config redissonSettings(RedisProperties properties) {
        Config config = new Config();
        SingleServerConfig server = config.useSingleServer()
                .setAddress((properties.isSsl() ? "rediss://" : "redis://")
                        + properties.getHost() + ":" + properties.getPort())
                .setDatabase(properties.getDatabase());
        if (properties.getPassword() != null && !properties.getPassword().isEmpty()) {
            server.setPassword(properties.getPassword());
        }
        return config;
    }

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(Config redissonSettings) {
        return Redisson.create(redissonSettings);
    }

}
