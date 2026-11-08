package com.bee.smartlife.utils;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "SMARTLIFE_INTEGRATION_TESTS", matches = "true")
class RedisIdWorkerIT {
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    private final Set<String> ownedKeys = new HashSet<>();

    @BeforeAll
    static void connect() {
        var config = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("SMARTLIFE_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(Objects.requireNonNull(System.getenv("SMARTLIFE_REDIS_PORT"),
                        "Set the dedicated test Redis port explicitly")));
        config.setDatabase(Integer.parseInt(System.getenv().getOrDefault("SMARTLIFE_REDIS_DATABASE", "0")));
        String password = System.getenv("SMARTLIFE_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) config.setPassword(password);
        connection = new LettuceConnectionFactory(config);
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
    }

    @AfterEach
    void cleanOwnedCounters() {
        if (!ownedKeys.isEmpty()) redis.delete(ownedKeys);
    }

    @AfterAll
    static void disconnect() {
        if (connection != null) connection.destroy();
    }

    @Test
    void independentWorkersShareTheRealRedisSequenceUnderConcurrency() throws Exception {
        String prefix = newPrefix();
        RedisIdWorker first = worker();
        RedisIdWorker second = worker();
        var ids = new HashSet<Long>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<Long>>();
            for (int i = 0; i < 400; i++) {
                RedisIdWorker selected = i % 2 == 0 ? first : second;
                tasks.add(pool.submit(() -> selected.nextId(prefix, fixed("2026-11-08T10:00:00Z"))));
            }
            for (Future<Long> task : tasks) ids.add(task.get(20, TimeUnit.SECONDS));
        }
        assertThat(ids).hasSize(400);
        assertThat(redis.opsForValue().get("icr:" + prefix + ":2026:11:08")).isEqualTo("400");
    }

    @Test
    void firstIdsOnAdjacentUtcDatesDoNotCollide() {
        String prefix = newPrefix();
        long first = worker().nextId(prefix, fixed("2026-11-08T23:59:59Z"));
        long second = worker().nextId(prefix, fixed("2026-11-09T00:00:00Z"));
        assertThat(first & 0xFFFF_FFFFL).isEqualTo(1);
        assertThat(second & 0xFFFF_FFFFL).isEqualTo(1);
        assertThat(second >>> 32).isEqualTo((first >>> 32) + 1);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void twoSuccessiveJvmProcessesContinueTheRetainedRedisCounter() throws Exception {
        String prefix = newPrefix();
        long first = runProcess(prefix);
        long restarted = runProcess(prefix);
        assertThat(first & 0xFFFF_FFFFL).isEqualTo(1);
        assertThat(restarted & 0xFFFF_FFFFL).isEqualTo(2);
        assertThat(restarted).isNotEqualTo(first);
    }

    private long runProcess(String prefix) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path log = Files.createTempFile("smart-life-id-probe-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    ProcessProbe.class.getName(), prefix)
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("child JVM timeout").isTrue();
            String output = Files.readString(log);
            assertThat(process.exitValue()).as("child output: %s", output).isZero();
            return output.lines().filter(line -> line.startsWith("ID=")).mapToLong(line -> Long.parseLong(line.substring(3)))
                    .findFirst().orElseThrow(() -> new AssertionError("No ID in child output: " + output));
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(log);
        }
    }

    private String newPrefix() {
        String prefix = "order-id-it-" + UUID.randomUUID();
        ownedKeys.add("icr:" + prefix + ":2026:11:08");
        ownedKeys.add("icr:" + prefix + ":2026:11:09");
        return prefix;
    }

    private static RedisIdWorker worker() {
        var worker = new RedisIdWorker();
        ReflectionTestUtils.setField(worker, "stringRedisTemplate", redis);
        return worker;
    }

    private static Clock fixed(String time) {
        return Clock.fixed(Instant.parse(time), ZoneOffset.UTC);
    }

    public static class ProcessProbe {
        public static void main(String[] args) {
            connect();
            try {
                System.out.println("ID=" + worker().nextId(args[0], fixed("2026-11-08T10:00:00Z")));
            } finally {
                disconnect();
            }
        }
    }
}
