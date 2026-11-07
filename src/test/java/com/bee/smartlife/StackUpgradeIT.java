package com.bee.smartlife;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Run only against the dedicated compose.dev.yaml services and synthetic seed data. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "SMARTLIFE_INTEGRATION_TESTS", matches = "true")
class StackUpgradeIT {
    private static final Path UPLOADS = Path.of("target", "integration-uploads").toAbsolutePath();

    @DynamicPropertySource
    static void isolatedUploads(DynamicPropertyRegistry registry) {
        registry.add("smartlife.upload-dir", UPLOADS::toString);
    }

    @Autowired TestRestTemplate http;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    @Test
    void protectedEndpointStillRequiresLogin() {
        assertThat(http.getForEntity("/user/me", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity("/user/me/", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // The original exact interceptor exclusions do not include a slash suffix.
        assertThat(http.postForEntity("/user/login/", Map.of(), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void shopQueriesPreserveEnvelopePaginationAndCacheReads() {
        JsonNode first = get("/shop/1");
        assertThat(first.path("data").path("name").asText()).isEqualTo("SmartLife Cafe");
        assertThat(get("/shop/1")).isEqualTo(first);
        assertThat(get("/shop/1/")).isEqualTo(first);
        assertThat(get("/shop/of/type?typeId=1&current=1").path("data").size()).isEqualTo(1);
        assertThat(get("/shop/of/type?typeId=1&current=2").path("data").isEmpty()).isTrue();
        assertThat(get("/shop/of/name?name=SmartLife&current=1").path("data").size()).isEqualTo(2);
        assertThat(get("/shop/of/name?name=SmartLife&current=2").path("data").isEmpty()).isTrue();
        assertThat(get("/shop-type/list").path("data").size()).isEqualTo(2);
        assertThat(get("/shop-type/list/").path("data").size()).isEqualTo(2);
        assertThat(get("/blog/hot?current=1").path("data").isArray()).isTrue();
    }

    @Test
    void multipartUploadAndDeleteUseConfiguredDirectory() throws IOException {
        var form = new LinkedMultiValueMap<String, Object>();
        byte[] content = "migration-upload-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        form.add("file", new ByteArrayResource(content) {
            @Override public String getFilename() { return "fixture.png"; }
        });
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        JsonNode result = http.postForObject("/upload/blog", new HttpEntity<>(form, headers), JsonNode.class);
        assertSuccess(result);
        String name = result.path("data").asText();
        Path uploaded = UPLOADS.resolve(name.substring(1));
        try {
            assertThat(Files.readAllBytes(uploaded)).isEqualTo(content);
            get("/upload/blog/delete?name=" + name);
            assertThat(uploaded).doesNotExist();
        } finally {
            Files.deleteIfExists(uploaded);
        }
    }

    @Test
    void loginLuaAndRealMqConsumptionPersistExactlyOneAcceptedOrder() {
        String phone = "139" + ThreadLocalRandom.current().nextLong(10_000_000L, 99_999_999L);
        String token = null;
        Long userId = null;
        Long voucherId = null;
        try {
            redis.opsForValue().set("login:code:" + phone, "123456", Duration.ofMinutes(2));
            JsonNode login = http.postForObject("/user/login", Map.of("phone", phone, "code", "123456"), JsonNode.class);
            assertSuccess(login);
            token = login.path("data").asText();
            var headers = new HttpHeaders();
            headers.set("authorization", token);
            JsonNode me = http.exchange("/user/me", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class).getBody();
            assertSuccess(me);
            userId = me.path("data").path("id").asLong();
            assertThat(userId).isPositive();
            JsonNode slashMe = http.exchange("/user/me/", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class).getBody();
            assertSuccess(slashMe);
            assertThat(slashMe.path("data").path("id").asLong()).isEqualTo(userId);

            JsonNode created = http.postForObject("/voucher/seckill", Map.of(
                    "shopId", 1, "title", "Stack upgrade fixture", "payValue", 100,
                    "actualValue", 200, "type", 1, "status", 1, "stock", 2,
                    "beginTime", LocalDateTime.now().minusHours(1).toString(),
                    "endTime", LocalDateTime.now().plusHours(1).toString()), JsonNode.class);
            assertSuccess(created);
            voucherId = created.path("data").asLong();
            assertThat(voucherId).isPositive();
            JsonNode accepted = http.exchange("/voucher-order/seckill/" + voucherId,
                    HttpMethod.POST, new HttpEntity<>(headers), JsonNode.class).getBody();
            assertSuccess(accepted);
            long orderId = accepted.path("data").asLong();
            long finalVoucherId = voucherId;
            long finalUserId = userId;
            await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE id=? AND user_id=? AND voucher_id=?",
                        Long.class, orderId, finalUserId, finalVoucherId)).isEqualTo(1L);
            });
            assertThat(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, voucherId)).isEqualTo(1);
            assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("1");
            JsonNode duplicate = http.exchange("/voucher-order/seckill/" + voucherId,
                    HttpMethod.POST, new HttpEntity<>(headers), JsonNode.class).getBody();
            assertThat(duplicate.path("success").asBoolean()).isFalse();
            assertThat(duplicate.path("errorMsg").asText()).isEqualTo("不能重复下单");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?", Long.class, voucherId)).isEqualTo(1L);
        } finally {
            if (voucherId != null) {
                jdbc.update("DELETE FROM tb_voucher_order WHERE voucher_id=?", voucherId);
                jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id=?", voucherId);
                jdbc.update("DELETE FROM tb_voucher WHERE id=?", voucherId);
                redis.delete(java.util.List.of("seckill:stock:" + voucherId, "seckill:order:" + voucherId));
            }
            if (userId != null) jdbc.update("DELETE FROM tb_user WHERE id=?", userId);
            if (token != null) redis.delete("login:token:" + token);
            redis.delete("login:code:" + phone);
        }
    }

    private JsonNode get(String url) {
        JsonNode result = http.getForObject(url, JsonNode.class);
        assertSuccess(result);
        return result;
    }

    private void assertSuccess(JsonNode result) {
        assertThat(result).isNotNull();
        assertThat(result.path("success").asBoolean()).as("response: %s", result).isTrue();
    }
}
