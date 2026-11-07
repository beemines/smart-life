package com.bee.smartlife.config;

import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

import static org.junit.jupiter.api.Assertions.*;

class RedissonConfigTest {
    @Test
    void usesTheSameHostPortDatabaseAndPasswordAsSpringRedis() {
        RedisProperties properties = new RedisProperties();
        properties.setHost("redis.example.test");
        properties.setPort(6388);
        properties.setDatabase(3);
        properties.setPassword("test-only-password");
        Config config = new RedissonConfig().redissonSettings(properties);
        SingleServerConfig server = config.useSingleServer();
        assertEquals("redis://redis.example.test:6388", server.getAddress());
        assertEquals(3, server.getDatabase());
        assertEquals("test-only-password", config.getPassword());
    }

    @Test
    void supportsTlsWithoutRequiringAPassword() {
        RedisProperties properties = new RedisProperties();
        properties.getSsl().setEnabled(true);
        SingleServerConfig server = new RedissonConfig().redissonSettings(properties).useSingleServer();
        assertEquals("rediss://localhost:6379", server.getAddress());
        assertNull(server.getPassword());
    }
}
