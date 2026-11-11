package com.bee.smartlife;

import com.bee.smartlife.mq.VoucherOrderMQProducer;
import com.bee.smartlife.entity.VoucherOrder;
import com.bee.smartlife.service.IVoucherOrderService;
import com.bee.smartlife.service.impl.VoucherOrderPersistence;
import com.bee.smartlife.service.impl.VoucherOrderReservation;
import com.bee.smartlife.service.impl.OrderRecoveryService;
import com.bee.smartlife.service.impl.OrderRecoveryJob;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import com.bee.smartlife.mq.VoucherOrderMQConsumer;
import cn.hutool.json.JSONUtil;
import org.redisson.api.RedissonClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.sql.Statement;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"smartlife.orders.consumer-enabled=false", "smartlife.orders.recovery-enabled=false"})
@EnabledIfEnvironmentVariable(named = "SMARTLIFE_INTEGRATION_TESTS", matches = "true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderRequestRecoveryIT {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired IVoucherOrderService orders;
    @Autowired VoucherOrderPersistence persistence;
    @Autowired VoucherOrderReservation reservation;
    @Autowired OrderRecoveryService recovery;
    @Autowired DefaultMQProducer realProducer;
    @Autowired VoucherOrderMQConsumer listener;
    @Autowired RedissonClient redisson;
    @Autowired OrderRecoveryJob recoveryJob;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean VoucherOrderMQProducer producer;
    private long voucherId;
    private long userId;
    private String token;
    private String requestKey;

    @BeforeEach
    void fixture() throws Exception {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO tb_voucher(shop_id,title,pay_value,actual_value,type,status) VALUES (1,?,100,200,1,1)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "request-recovery-it-" + UUID.randomUUID());
            return statement;
        }, key);
        voucherId = Objects.requireNonNull(key.getKey()).longValue();
        userId = ThreadLocalRandom.current().nextLong(3_000_000_000L, 4_000_000_000L);
        jdbc.update("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES (?,10,DATE_SUB(NOW(),INTERVAL 1 HOUR),DATE_ADD(NOW(),INTERVAL 1 HOUR))", voucherId);
        OrderTestActivity.ready(jdbc,voucherId);
        redis.opsForValue().set("seckill:stock:" + voucherId, "10");
        token = UUID.randomUUID().toString();
        requestKey = UUID.randomUUID().toString();
        redis.opsForHash().putAll("login:token:" + token, Map.of("id", Long.toString(userId), "nickName", "fixture"));
        var sent = new SendResult();
        sent.setSendStatus(SendStatus.SEND_OK);
        doReturn(sent).when(producer).sendSeckillOrder(any());
    }

    @AfterEach
    void cleanOwnedFixture() {
        for (String table : List.of("tb_voucher_order_recovery", "tb_voucher_order_request")) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", Integer.class, table);
            if (exists != null && exists > 0) jdbc.update("DELETE FROM " + table + " WHERE voucher_id=?", voucherId);
        }
        jdbc.update("DELETE FROM tb_voucher_order_failure WHERE voucher_id=?", voucherId);
        jdbc.update("DELETE FROM tb_voucher_order WHERE voucher_id=?", voucherId);
        jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id=?", voucherId);
        jdbc.update("DELETE FROM tb_voucher_publication WHERE voucher_id=?", voucherId);
        jdbc.update("DELETE FROM tb_voucher WHERE id=?", voucherId);
        redis.delete(List.of("seckill:stock:" + voucherId, "seckill:order:" + voucherId,
                "seckill:reservation:" + voucherId, "login:token:" + token));
    }

    @Test
    void acceptedOrderIdMustCrossTheBrowserBoundaryAsAString() {
        var response = post();
        assertThat(response.path("success").asBoolean()).isTrue();
        assertThat(response.path("data").isTextual()).isTrue();
    }

    @Test
    void retryingTheSameHttpRequestKeyMustReturnTheSameAcceptedOrder() {
        var first = post();
        var second = post();
        assertThat(second.path("success").asBoolean()).isTrue();
        assertThat(second.path("data").asText()).isEqualTo(first.path("data").asText());
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("9");
    }

    @Test
    void uncertainSendMustKeepTheReservationAndAnOwnerQueryablePendingResult() throws Exception {
        when(producer.sendSeckillOrder(any())).thenThrow(new java.net.SocketTimeoutException("fixture response lost"));
        var accepted = post();
        assertThat(accepted.path("success").asBoolean()).isTrue();
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("9");
        var result = get("/voucher-order/request/" + requestKey, token);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().path("data").path("state").asText()).isEqualTo("PENDING");
    }

    @Test
    void queuedOrderIsQueryableWithoutPretendingItAlreadyExistsInTheOrdersTable() {
        var accepted = post();
        var result = get("/voucher-order/result/" + accepted.path("data").asText(), token);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().path("data").path("state").asText()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?", Long.class, voucherId)).isZero();
    }

    @Test
    void resumingAnOwnedReservationMustNotDeductOrRejectItAgain() {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource("seckill.lua"));
        script.setResultType(Long.class);
        var keys = List.of("seckill:stock:" + voucherId, "seckill:order:" + voucherId, "seckill:reservation:" + voucherId);
        assertThat(redis.execute(script, keys, Long.toString(voucherId), Long.toString(userId), "123456789", "1", "1", "946684800", "4102444800")).isZero();
        assertThat(redis.execute(script, keys, Long.toString(voucherId), Long.toString(userId), "123456789", "1", "1", "946684800", "4102444800")).isEqualTo(4);
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("9");
    }

    @Test
    void otherUsersAndUnknownIdsHaveTheSameResultAndAnonymousQueriesRequireLogin() {
        String id = post().path("data").asText();
        String otherToken = UUID.randomUUID().toString();
        redis.opsForHash().putAll("login:token:" + otherToken, Map.of("id", Long.toString(userId + 1)));
        try {
            var forbidden = get("/voucher-order/result/" + id, otherToken);
            var absent = get("/voucher-order/result/1", otherToken);
            assertThat(forbidden.getBody()).isEqualTo(absent.getBody());
            assertThat(forbidden.getBody().path("success").asBoolean()).isFalse();
            assertThat(get("/voucher-order/request/" + requestKey, otherToken).getBody().path("success").asBoolean()).isFalse();
            assertThat(http.getForEntity("/voucher-order/result/" + id, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        } finally { redis.delete("login:token:" + otherToken); }
    }

    @Test
    void requestKeyCannotBeReusedForAnotherVoucher() {
        post();
        var headers = new HttpHeaders();
        headers.set("authorization", token);
        headers.set("Idempotency-Key", requestKey);
        var response = http.exchange("/voucher-order/seckill/" + (voucherId + 100000), HttpMethod.POST,
                new HttpEntity<>(headers), JsonNode.class).getBody();
        assertThat(response.path("success").asBoolean()).isFalse();
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("9");
    }

    @Test
    void resultBecomesCreatedOnlyAfterTheRealOrderTransactionCommits() {
        long id = Long.parseLong(post().path("data").asText());
        orders.createVoucherOrder(order(id));
        var result = get("/voucher-order/result/" + id, token).getBody().path("data");
        assertThat(result.path("state").asText()).isEqualTo("CREATED");
        assertThat(result.path("orderId").isTextual()).isTrue();
        assertThat(dbStock()).isEqualTo(9);
    }

    @Test
    void freshInFlightPreviewAndExecutionDoNotCompensateOrRepublish() throws Exception {
        long id = Long.parseLong(post().path("data").asText());
        var before = recovery.preview(id, 20, 120);
        assertThat(before).hasSize(1);
        assertThat(before.getFirst().action()).isEqualTo("WAIT");
        assertThat(recovery.execute(id, 20, 120)).isEmpty();
        verify(producer, times(1)).sendSeckillOrder(any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order_recovery WHERE order_id=?", Long.class, id)).isZero();
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("9");
    }

    @Test
    void staleIntentBeforeReservationCanBeResumedWithTheOriginalIdAndAudited() {
        long id = ThreadLocalRandom.current().nextLong(5_000_000_000_000L, 6_000_000_000_000L);
        persistence.accept(order(id), requestKey);
        age(id);
        assertThat(recovery.preview(id, 20, 120).getFirst().action()).isEqualTo("PREPARE");
        assertThat(recovery.execute(id, 20, 120).getFirst().action()).isEqualTo("REQUEUED");
        orders.createVoucherOrder(order(id));
        assertThat(dbStock()).isEqualTo(9);
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("9");
        assertThat(jdbc.queryForObject("SELECT outcome FROM tb_voucher_order_recovery WHERE order_id=?", String.class, id)).isEqualTo("SUCCEEDED");
        assertThat(recovery.execute(id, 20, 120)).isEmpty();
    }

    @Test
    void staleIntentAfterReservationResumesWithoutASecondRedisDeduction() {
        long id = ThreadLocalRandom.current().nextLong(5_000_000_000_000L, 6_000_000_000_000L);
        persistence.accept(order(id), requestKey);
        reservation.reserve(order(id));
        age(id);
        recovery.execute(id, 20, 120);
        orders.createVoucherOrder(order(id));
        assertThat(dbStock()).isEqualTo(9);
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("9");
    }

    @Test
    void uncertainSendIsRepublishedUsingTheSameIdAndRepeatedRecoveryDoesNotDuplicateIt() throws Exception {
        when(producer.sendSeckillOrder(any())).thenThrow(new java.net.SocketTimeoutException("fixture unknown send"));
        long id = Long.parseLong(post().path("data").asText());
        var sent = new SendResult(); sent.setSendStatus(SendStatus.SEND_OK);
        doReturn(sent).when(producer).sendSeckillOrder(any());
        age(id);
        assertThat(recovery.execute(id, 20, 120).getFirst().action()).isEqualTo("REQUEUED");
        orders.createVoucherOrder(order(id));
        assertThat(recovery.execute(id, 20, 120)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE id=?", Long.class, id)).isEqualTo(1);
        assertThat(dbStock()).isEqualTo(9);
    }

    @Test
    void pendingRequestWhoseOwnershipIsLostMustNotBlindlyReserveAgain() {
        long id = Long.parseLong(post().path("data").asText());
        redis.opsForHash().delete("seckill:reservation:" + voucherId, Long.toString(userId));
        redis.opsForSet().remove("seckill:order:" + voucherId, Long.toString(userId));
        age(id);
        recovery.execute(id, 20, 120);
        assertThat(get("/voucher-order/result/" + id, token).getBody().path("data").path("state").asText()).isEqualTo("REVIEW");
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("9");
        assertThat(dbStock()).isEqualTo(10);
    }

    @Test
    void staleCompensationIsNotStarvedByFreshRequestsInABoundedBatch() {
        long fresh = ThreadLocalRandom.current().nextLong(5_000_000_000_000L, 6_000_000_000_000L);
        long stale = fresh + 1;
        persistence.accept(order(fresh), requestKey);
        jdbc.update("INSERT INTO tb_voucher_order_failure(order_id,user_id,voucher_id,reason,compensation_status,update_time) VALUES (?,?,?,'REJECTED_OUT_OF_STOCK','pending',DATE_SUB(NOW(),INTERVAL 10 MINUTE))", stale, userId + 1, voucherId);
        assertThat(recovery.preview(null, 1, 120).getFirst().orderId()).isEqualTo(Long.toString(stale));
        assertThat(recovery.execute(null, 1, 120).getFirst().orderId()).isEqualTo(Long.toString(stale));
        assertThat(jdbc.queryForObject("SELECT compensation_status FROM tb_voucher_order_failure WHERE order_id=?", String.class, stale)).isEqualTo("done");
    }

    @Test
    void actualProcessExitAfterReservationRecoversWithOneReservationAndOneOrder() throws Exception {
        crashAndRecover("RESERVE");
    }

    @Test
    void actualProcessExitAfterSendingRecoversWithoutDuplicatingTheBusinessResult() throws Exception {
        crashAndRecover("SEND");
    }

    @Test
    void persistentSendFailureIsAuditedAsUnconfirmedRatherThanSuccessful() throws Exception {
        when(producer.sendSeckillOrder(any())).thenThrow(new java.net.SocketTimeoutException("still unavailable"));
        long id = Long.parseLong(post().path("data").asText());
        age(id);
        var result = recovery.execute(id, 20, 120).getFirst();
        assertThat(result.outcome()).isEqualTo("RETRY");
        assertThat(jdbc.queryForObject("SELECT outcome FROM tb_voucher_order_recovery WHERE order_id=?", String.class, id)).isEqualTo("RETRY");
        assertThat(get("/voucher-order/result/" + id, token).getBody().path("data").path("state").asText()).isEqualTo("PENDING");
    }

    @Test
    void aManualHeadDoesNotStarveTheNextRecoverableRequestAcrossScans() {
        long first = Long.parseLong(post().path("data").asText());
        long second = first + 1;
        persistence.accept(order(second).setUserId(userId + 1), requestKey + "-next");
        jdbc.update("INSERT INTO tb_voucher_order(id,user_id,voucher_id) VALUES (?,?,?)", first, userId, voucherId);
        jdbc.update("INSERT INTO tb_voucher_order_failure(order_id,user_id,voucher_id,reason,compensation_status,update_time) VALUES (?,?,?,'REJECTED_OUT_OF_STOCK','pending',DATE_SUB(NOW(),INTERVAL 30 MINUTE))", first, userId, voucherId);
        jdbc.update("UPDATE tb_voucher_order_request SET update_time=DATE_SUB(NOW(),INTERVAL 30 MINUTE) WHERE order_id=?", first);
        age(second);
        recovery.execute(null, 1, 120);
        var next = recovery.execute(null, 1, 120);
        assertThat(next).hasSize(1);
        assertThat(next.getFirst().orderId()).isEqualTo(Long.toString(second));
    }

    @Test
    void cliExecutesOnlyTheIdsThatItPrintedInItsPreview() throws Exception {
        long manual = ThreadLocalRandom.current().nextLong(5_000_000_000_000L, 6_000_000_000_000L);
        long pending = manual + 1;
        persistence.accept(order(manual), requestKey + "-manual");
        persistence.accept(order(pending), requestKey + "-pending");
        reservation.reserve(order(pending)); persistence.markSending(order(pending));
        jdbc.update("UPDATE tb_voucher_order_request SET state='REVIEW',update_time=DATE_SUB(NOW(),INTERVAL 20 MINUTE) WHERE order_id=?", manual);
        jdbc.update("INSERT INTO tb_voucher_order_failure(order_id,user_id,voucher_id,reason,compensation_status,update_time) VALUES (?,?,?,'REJECTED_RESERVATION_INVALID','manual',DATE_SUB(NOW(),INTERVAL 20 MINUTE))", manual, userId, voucherId);
        age(pending);
        var original = System.out;
        var captured = new ByteArrayOutputStream();
        ReflectionTestUtils.setField(recoveryJob, "cli", true);
        ReflectionTestUtils.setField(recoveryJob, "batch", 1);
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            recoveryJob.run(new DefaultApplicationArguments("--recover.execute=true"));
        } finally {
            System.setOut(original);
            ReflectionTestUtils.setField(recoveryJob, "cli", false);
            ReflectionTestUtils.setField(recoveryJob, "batch", 100);
        }
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("ORDER_RECOVERY_PREVIEW=").contains(Long.toString(manual));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order_recovery WHERE order_id=?", Long.class, pending)).isZero();
    }

    @Test
    void staleDetectionUsesTheDatabaseClockInAPositiveOffsetSession() {
        long id = Long.parseLong(post().path("data").asText()); age(id);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET time_zone='+08:00'");
            try { assertThat(recovery.preview(id, 20, 120).getFirst().action()).isEqualTo("RESEND"); }
            finally { jdbc.execute("SET time_zone='+00:00'"); }
        });
    }

    @Test
    void aNegativeOffsetSessionMustNotMakeAFreshRequestImmediatelyStale() {
        long id = Long.parseLong(post().path("data").asText());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET time_zone='-08:00'");
            try { assertThat(recovery.preview(id, 20, 120).getFirst().action()).isEqualTo("WAIT"); }
            finally { jdbc.execute("SET time_zone='+00:00'"); }
        });
    }

    private void crashAndRecover(String phase) throws Exception {
        String topic = "smartlife-crash-" + UUID.randomUUID().toString().replace("-", "");
        Path output = Path.of("target", "crash-" + phase + "-" + requestKey + ".log").toAbsolutePath();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                OrderRecoveryCrashWorker.class.getName(), phase, Long.toString(userId), Long.toString(voucherId), requestKey, topic)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(child.waitFor(90, TimeUnit.SECONDS)).as("child JVM timeout; log %s", output).isTrue();
            assertThat(child.exitValue()).as("child output: %s", Files.readString(output)).isEqualTo(73);
            assertThat(Files.readString(output)).contains("CRASH_BOUNDARY=" + phase);
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
        long id = Objects.requireNonNull(jdbc.queryForObject("SELECT order_id FROM tb_voucher_order_request WHERE user_id=? AND request_key=?", Long.class, userId, requestKey));
        await().atMost(Duration.ofSeconds(45)).until(() -> !redisson.getLock("lock:order:" + userId).isLocked()
                && !redisson.getLock("lock:order-id:" + id).isLocked());
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("9");
        doAnswer(invocation -> {
            com.bee.smartlife.dto.VoucherOrderMessage message = invocation.getArgument(0);
            return realProducer.send(new Message(topic, JSONUtil.toJsonStr(message).getBytes(StandardCharsets.UTF_8)));
        })
                .when(producer).sendSeckillOrder(any());
        age(id);
        assertThat(recovery.execute(id, 20, 120).getFirst().action()).isEqualTo("REQUEUED");
        var received = new CountDownLatch("SEND".equals(phase) ? 2 : 1);
        var consumer = new DefaultMQPushConsumer("recovery-it-" + UUID.randomUUID());
        consumer.setInstanceName("recovery-" + UUID.randomUUID());
        consumer.setNamesrvAddr(System.getenv().getOrDefault("SMARTLIFE_MQ_NAME_SERVER", "127.0.0.1:19876"));
        consumer.setVipChannelEnabled(false);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(topic, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            var result = listener.consumeMessage(msgs, context);
            if (result == ConsumeConcurrentlyStatus.CONSUME_SUCCESS) msgs.forEach(ignored -> received.countDown());
            return result;
        });
        try {
            consumer.start();
            assertThat(received.await(70, TimeUnit.SECONDS)).as("real broker deliveries after %s crash", phase).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE id=?", Long.class, id)).isEqualTo(1);
            assertThat(dbStock()).isEqualTo(9);
            assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("9");
            assertThat(get("/voucher-order/result/" + id, token).getBody().path("data").path("state").asText()).isEqualTo("CREATED");
            assertThat(recovery.execute(id, 20, 120)).isEmpty();
        } finally { consumer.shutdown(); }
    }

    private VoucherOrder order(long id) {
        return new VoucherOrder().setId(id).setUserId(userId).setVoucherId(voucherId);
    }

    private void age(long id) {
        jdbc.update("UPDATE tb_voucher_order_request SET update_time=DATE_SUB(NOW(),INTERVAL 10 MINUTE) WHERE order_id=?", id);
    }

    private int dbStock() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, voucherId));
    }

    private JsonNode post() {
        var headers = new HttpHeaders();
        headers.set("authorization", token);
        headers.set("Idempotency-Key", requestKey);
        return http.exchange("/voucher-order/seckill/" + voucherId, HttpMethod.POST,
                new HttpEntity<>(headers), JsonNode.class).getBody();
    }

    private ResponseEntity<JsonNode> get(String path, String auth) {
        var headers = new HttpHeaders();
        headers.set("authorization", auth);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }
}
