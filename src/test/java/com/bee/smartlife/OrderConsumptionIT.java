package com.bee.smartlife;

import com.bee.smartlife.entity.VoucherOrder;
import com.bee.smartlife.dto.UserDTO;
import com.bee.smartlife.dto.VoucherOrderMessage;
import com.bee.smartlife.mq.VoucherOrderMQProducer;
import com.bee.smartlife.mq.VoucherOrderMQConsumer;
import com.bee.smartlife.mapper.VoucherOrderMapper;
import com.bee.smartlife.mapper.VoucherOrderFailureMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bee.smartlife.service.IVoucherOrderService;
import com.bee.smartlife.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.api.RedissonClient;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "SMARTLIFE_INTEGRATION_TESTS", matches = "true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderConsumptionIT {
    private static final AtomicLong IDS = new AtomicLong(ThreadLocalRandom.current()
            .nextLong(3_000_000_000_000L, 4_000_000_000_000L));
    @Autowired IVoucherOrderService orders;
    @Autowired org.springframework.boot.test.web.client.TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired RedissonClient redisson;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean VoucherOrderMQProducer producer;
    @MockitoSpyBean VoucherOrderMQConsumer consumer;
    @MockitoSpyBean VoucherOrderMapper orderMapper;
    @MockitoSpyBean VoucherOrderFailureMapper failureMapper;
    private long voucherId;
    private long userId;
    private final List<Long> ownedVouchers = new ArrayList<>();

    @Test
    void hundredDistinctHttpUsersCompeteForTenThroughRealMq() throws Exception {
        assertHttpContention(100,100,10);
    }

    @Test
    void sameUserConcurrentHttpRequestsCreateOnlyOneOrderThroughRealMq() throws Exception {
        assertHttpContention(20,1,1);
    }

    private final java.util.Set<Long> retainedVouchers=new java.util.HashSet<>();

    private void assertHttpContention(int requests,int distinctUsers,int expectedOrders) throws Exception {
        var tokens=new ArrayList<String>();var users=new ArrayList<Long>();
        var attempts=new ArrayList<HttpOrderEvidence.Attempt>();
        var client=new org.springframework.boot.test.web.client.TestRestTemplate(new org.springframework.boot.web.client.RestTemplateBuilder()
            .rootUri(http.getRootUri()).setConnectTimeout(Duration.ofSeconds(3)).setReadTimeout(Duration.ofSeconds(15)));
        var pool=Executors.newFixedThreadPool(requests);
        var ready=new CountDownLatch(requests);var start=new CountDownLatch(1);
        boolean drained=false;
        redis.opsForValue().set("seckill:stock:"+voucherId,"10");
        try {
            for(int i=0;i<distinctUsers;i++) {
                long user=IDS.incrementAndGet();String token=UUID.randomUUID().toString();
                jdbc.update("INSERT INTO tb_user(id,phone,nick_name) VALUES (?,?,'http contention fixture')",user,"h"+(user%1000000000));
                users.add(user);tokens.add(token);
                redis.opsForHash().putAll("login:token:"+token,java.util.Map.of("id",Long.toString(user),"nickName","contention fixture"));
                assertThat(redis.expire("login:token:"+token,Duration.ofMinutes(15))).isTrue();
                assertThat(redis.getExpire("login:token:"+token)).as("synthetic session must expire even if setup later fails").isPositive().isLessThanOrEqualTo(900L);
            }
            var futures=new ArrayList<java.util.concurrent.Future<HttpOrderEvidence.Attempt>>();
            for(int i=0;i<requests;i++) {
                int caller=i;long user=users.get(i%distinctUsers);String token=tokens.get(i%distinctUsers),key=UUID.randomUUID().toString();
                futures.add(pool.submit(()->{
                    ready.countDown();assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();
                    var headers=new org.springframework.http.HttpHeaders();headers.set("authorization",token);headers.set("Idempotency-Key",key);
                    var response=client.exchange("/voucher-order/seckill/"+voucherId,org.springframework.http.HttpMethod.POST,
                        new org.springframework.http.HttpEntity<>(headers),com.fasterxml.jackson.databind.JsonNode.class);
                    assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.OK);
                    var body=response.getBody();assertThat(body).isNotNull();
                    if(body.path("success").asBoolean()) {long order=body.path("data").asLong();assertThat(order).isPositive();return new HttpOrderEvidence.Attempt(caller,user,key,order,null);}
                    String error=body.path("errorMsg").asText();assertThat(error).isIn("库存不足","不能重复下单");
                    return new HttpOrderEvidence.Attempt(caller,user,key,null,error);
                }));
            }
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);start.countDown();
            for(var future:futures) {
                long remaining=deadline-System.nanoTime();if(remaining<=0)throw new java.util.concurrent.TimeoutException("shared HTTP deadline exceeded");
                attempts.add(future.get(remaining,TimeUnit.NANOSECONDS));
            }
            var httpRows=new ArrayList<String>();httpRows.add("caller,user_id,request_key,accepted_order_id,business_error");
            for(var attempt:attempts)httpRows.add(HttpOrderEvidence.csv(attempt.caller(),attempt.user(),attempt.key(),attempt.acceptedId(),attempt.error()));
            java.nio.file.Files.write(java.nio.file.Path.of("target","http-contention-"+voucherId+"-accepted.csv"),httpRows);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(()->{
                // Schedulers are disabled. Both user-lock and shared activity-lock losers
                // can remain PREPARING; recover only this fixture after real MQ progress.
                if(orderCount()>0)
                    for(long pending:jdbc.queryForList("SELECT order_id FROM tb_voucher_order_request WHERE voucher_id=? AND state='PREPARING'",Long.class,voucherId))
                        orders.recoverOrder(pending,java.time.LocalDateTime.now().plusSeconds(1));
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order_request WHERE voucher_id=? AND state IN ('CREATED','REJECTED')",Integer.class,voucherId)).isEqualTo(requests);
            });
            drained=true;
            var outcomes=jdbc.query("SELECT order_id,user_id,voucher_id,request_key,state,reason FROM tb_voucher_order_request WHERE voucher_id=? ORDER BY order_id",
                (rs,n)->new HttpOrderEvidence.Outcome(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getString(5),rs.getString(6)),voucherId);
            var terminalRows=new ArrayList<String>();terminalRows.add("order_id,user_id,voucher_id,request_key,state,reason");
            for(var row:outcomes)terminalRows.add(HttpOrderEvidence.csv(row.order(),row.user(),row.voucher(),row.key(),row.state(),row.reason()));
            java.nio.file.Files.write(java.nio.file.Path.of("target","http-contention-"+voucherId+"-terminal.csv"),terminalRows);
            HttpOrderEvidence.assertIdentities(attempts,outcomes,voucherId);
            var created=outcomes.stream().filter(row->row.state().equals("CREATED")).toList();
            assertThat(created).hasSize(expectedOrders);
            assertThat(outcomes.stream().filter(row->row.state().equals("REJECTED")).count()).isEqualTo(requests-expectedOrders);
            long acceptedCount=attempts.stream().filter(row->row.acceptedId()!=null).count();
            // ACCEPTED acknowledges a durable intent, including PREPARING retries that
            // may later be rejected. SQL CREATED identities define effective orders.
            assertThat(acceptedCount).isBetween((long)expectedOrders,(long)requests);
            java.nio.file.Files.writeString(java.nio.file.Path.of("target","http-contention-"+voucherId+"-summary.json"),
                "{\"requests\":"+requests+",\"httpAccepted\":"+acceptedCount+",\"httpBusinessRejected\":"+(requests-acceptedCount)
                    +",\"effectiveCreated\":"+created.size()+",\"terminalRejected\":"+(requests-created.size())+"}");
            for(var row:outcomes)if(row.state().equals("REJECTED"))assertThat(row.reason()).isIn("REJECTED_OUT_OF_STOCK","REJECTED_ALREADY_PURCHASED");
            var orderRows=jdbc.queryForList("SELECT id,user_id,voucher_id,status FROM tb_voucher_order WHERE voucher_id=?",voucherId);
            assertThat(orderRows.stream().map(row->((Number)row.get("id")).longValue()).toList()).containsExactlyInAnyOrderElementsOf(created.stream().map(HttpOrderEvidence.Outcome::order).toList());
            var expectedReservations=new java.util.HashMap<Object,Object>();
            for(var row:created) {
                var persisted=orderRows.stream().filter(order->((Number)order.get("id")).longValue()==row.order()).findFirst().orElseThrow();
                assertThat(((Number)persisted.get("user_id")).longValue()).isEqualTo(row.user());
                assertThat(((Number)persisted.get("voucher_id")).longValue()).isEqualTo(voucherId);
                assertThat(((Number)persisted.get("status")).intValue()).isEqualTo(1);
                assertThat(expectedReservations.put(Long.toString(row.user()),Long.toString(row.order()))).as("one order per user").isNull();
            }
            assertThat(stock()).isEqualTo(10-expectedOrders).isNotNegative();
            assertThat(redis.opsForValue().get("seckill:stock:"+voucherId)).isEqualTo(Integer.toString(10-expectedOrders));
            assertThat(redis.opsForSet().members("seckill:order:"+voucherId)).containsExactlyInAnyOrderElementsOf(created.stream().map(row->Long.toString(row.user())).toList());
            assertThat(redis.opsForHash().entries("seckill:reservation:"+voucherId)).isEqualTo(expectedReservations);
        } finally {
            start.countDown();pool.shutdownNow();
            boolean stopped=pool.awaitTermination(20,TimeUnit.SECONDS);
            if(!stopped || !drained) {
                retainedVouchers.add(voucherId);
                java.nio.file.Files.writeString(java.nio.file.Path.of("target","http-contention-"+voucherId+"-retained.txt"),"Fixture retained: workersStopped="+stopped+", drained="+drained+", voucher="+voucherId+", users="+users);
                assertThat(stopped).as("workers must stop before fixture cleanup; retained voucher %s",voucherId).isTrue();
            } else {
                for(String table:List.of("tb_order_stock_return","tb_order_lifecycle"))jdbc.update("DELETE r FROM "+table+" r JOIN tb_voucher_order o ON o.id=r.order_id WHERE o.voucher_id=?",voucherId);
                for(long user:users)jdbc.update("DELETE FROM tb_user WHERE id=?",user);
                for(String token:tokens)redis.delete("login:token:"+token);
            }
        }
    }

    @AfterAll
    static void stopTheSpiedBrokerListener(@Autowired VoucherOrderMQConsumer consumer) {
        // The spy's ApplicationReadyEvent mutates its own consumer field.
        // Explicitly stop that live instance before Spring destroys the wrapped original.
        consumer.shutdown();
    }

    @BeforeEach
    void createOwnedFixture() {
        voucherId = newVoucher(10);
        userId = ThreadLocalRandom.current().nextLong(2_000_000_000L, 3_000_000_000L);
    }

    private long newVoucher(int stock) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO tb_voucher (shop_id,title,pay_value,actual_value,type,status) VALUES (1,?,100,200,1,1)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "order-consumption-it-" + UUID.randomUUID());
            return statement;
        }, key);
        long id = Objects.requireNonNull(key.getKey()).longValue();
        ownedVouchers.add(id);
        jdbc.update("INSERT INTO tb_seckill_voucher (voucher_id,stock,begin_time,end_time) VALUES (?,?,DATE_SUB(NOW(),INTERVAL 1 HOUR),DATE_ADD(NOW(),INTERVAL 1 HOUR))", id, stock);
        OrderTestActivity.ready(jdbc,id);
        return id;
    }

    @AfterEach
    void cleanOnlyOwnedFixture() {
        UserHolder.removeUser();
        for (long owned : ownedVouchers) {
            if(retainedVouchers.contains(owned))continue;
            jdbc.update("DELETE FROM tb_voucher_order_recovery WHERE voucher_id=?", owned);
            jdbc.update("DELETE FROM tb_voucher_order_request WHERE voucher_id=?", owned);
            jdbc.update("DELETE FROM tb_voucher_order_failure WHERE voucher_id=?", owned);
            jdbc.update("DELETE FROM tb_voucher_order WHERE voucher_id=?", owned);
            jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id=?", owned);
            jdbc.update("DELETE FROM tb_voucher_publication WHERE voucher_id=?", owned);
            jdbc.update("DELETE FROM tb_voucher WHERE id=?", owned);
            redis.delete(List.of("seckill:stock:" + owned, "seckill:order:" + owned,
                    "seckill:reservation:" + owned));
        }
    }

    @Test
    void heldUserLockMustNotReturnNormallyWithoutCreatingTheOrder() throws Exception {
        var lock = redisson.getLock("lock:order:" + userId);
        lock.lock();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var attempt = pool.submit(() -> orders.createVoucherOrder(order(IDS.incrementAndGet())));
            assertThatThrownBy(() -> attempt.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(orderCount()).isZero();
            assertThat(stock()).isEqualTo(10);
        } finally {
            lock.unlock();
        }
    }

    @Test
    void existingUserVoucherMustNotMaskAnOrderIdPayloadCollision() {
        long reusedId = IDS.incrementAndGet();
        jdbc.update("INSERT INTO tb_voucher_order(id,user_id,voucher_id) VALUES (?,?,?)", reusedId, userId + 1, voucherId);
        jdbc.update("INSERT INTO tb_voucher_order(id,user_id,voucher_id) VALUES (?,?,?)", IDS.incrementAndGet(), userId, voucherId);
        assertThatThrownBy(() -> orders.createVoucherOrder(order(reusedId)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(stock()).isEqualTo(10);
        assertThat(orderCount()).isEqualTo(2);
    }

    @Test
    void rejectedOrderMustRemainRejectedWhenStockIsLaterReplenished() {
        var message = order(IDS.incrementAndGet());
        jdbc.update("UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=?", voucherId);
        orders.createVoucherOrder(message);
        jdbc.update("UPDATE tb_seckill_voucher SET stock=10 WHERE voucher_id=?", voucherId);
        orders.createVoucherOrder(message);
        assertThat(orderCount()).isZero();
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    void reservationRecordsWhichOrderOwnsTheUserEligibility() {
        long id = IDS.incrementAndGet();
        assertThat(reserve(id)).isZero();
        assertThat(redis.opsForHash().get("seckill:reservation:" + voucherId, Long.toString(userId)))
                .isEqualTo(Long.toString(id));
    }

    @Test
    void replayingTheSameCompensationCannotIncreaseInventoryTwice() {
        long id = IDS.incrementAndGet();
        reserve(id);
        for (int attempt = 0; attempt < 10; attempt++) compensate(id, null);
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("10");
    }

    @Test
    void anOldCompensationCannotReleaseANewerOrdersReservation() {
        long first = IDS.incrementAndGet();
        long second = IDS.incrementAndGet();
        reserve(first);
        compensate(first, null);
        assertThat(reserve(second)).isZero();
        compensate(first, null);
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("9");
        assertThat(redis.opsForSet().isMember("seckill:order:" + voucherId, Long.toString(userId))).isTrue();
    }

    @Test
    void rejectingAConflictingRequestPreservesTheExistingOrdersEligibility() {
        long rejected = IDS.incrementAndGet();
        long existing = IDS.incrementAndGet();
        reserve(rejected);
        compensate(rejected, existing);
        compensate(rejected, existing);
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("10");
        assertThat(redis.opsForSet().isMember("seckill:order:" + voucherId, Long.toString(userId))).isTrue();
        assertThat(redis.opsForHash().get("seckill:reservation:" + voucherId, Long.toString(userId)))
                .isEqualTo(Long.toString(existing));
    }

    @Test
    void missingStockMustNotBeInventedDuringCompensation() {
        long id = IDS.incrementAndGet();
        reserve(id);
        redis.delete("seckill:stock:" + voucherId);
        compensate(id, null);
        assertThat(redis.hasKey("seckill:stock:" + voucherId)).isFalse();
        assertThat(redis.opsForSet().isMember("seckill:order:" + voucherId, Long.toString(userId))).isTrue();
    }

    @Test
    void legacyEligibilityWithoutAnOrderOwnerMustNotBeBlindlyReleased() {
        redis.opsForValue().set("seckill:stock:" + voucherId, "9");
        redis.opsForSet().add("seckill:order:" + voucherId, Long.toString(userId));
        compensate(IDS.incrementAndGet(), null);
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("9");
        assertThat(redis.opsForSet().isMember("seckill:order:" + voucherId, Long.toString(userId))).isTrue();
    }

    @Test
    void lostSendResponseAfterConsumerCommitMustNotReleaseSuccessfulReservation() throws Exception {
        authenticateFixtureUser();
        redis.opsForValue().set("seckill:stock:" + voucherId, "10");
        doAnswer(invocation -> {
            VoucherOrderMessage sent = invocation.getArgument(0);
            orders.createVoucherOrder(order(sent.getOrderId()));
            throw new java.net.SocketTimeoutException("fixture: broker accepted, response lost");
        }).when(producer).sendSeckillOrder(any());
        var response = orders.seckillVoucher(voucherId);
        assertThat(response.getSuccess()).isTrue();
        assertThat(orderCount()).isEqualTo(1);
        assertThat(stock()).isEqualTo(9);
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("9");
        assertThat(redis.opsForSet().isMember("seckill:order:" + voucherId, Long.toString(userId))).isTrue();
    }

    @Test
    void delayedDeliveryAfterUncertainSendUsesTheRetainedReservationExactlyOnce() throws Exception {
        authenticateFixtureUser();
        redis.opsForValue().set("seckill:stock:" + voucherId, "10");
        var delayed = new AtomicReference<VoucherOrderMessage>();
        doAnswer(invocation -> {
            delayed.set(invocation.getArgument(0));
            throw new java.net.SocketTimeoutException("fixture: delivery delayed beyond send timeout");
        }).when(producer).sendSeckillOrder(any());
        assertThat(orders.seckillVoucher(voucherId).getSuccess()).isTrue();
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("9");
        orders.createVoucherOrder(order(delayed.get().getOrderId()));
        orders.createVoucherOrder(order(delayed.get().getOrderId()));
        assertThat(orderCount()).isEqualTo(1);
        assertThat(stock()).isEqualTo(9);
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("9");
    }

    @Test
    void tenDeliveriesCommitOnlyOneOrderAndDeductInventoryOnce() {
        long id = IDS.incrementAndGet();
        reserve(id);
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(deliver(id)).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        }
        assertThat(orderCount()).isEqualTo(1);
        assertThat(stock()).isEqualTo(9);
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("9");
    }

    @Test
    void concurrentDeliveryAndItsRetryConvergeToOneBusinessResult() throws Exception {
        long id = IDS.incrementAndGet();
        reserve(id);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return deliver(id); });
            var second = pool.submit(() -> { start.await(); return deliver(id); });
            start.countDown();
            var firstStatus = first.get(10, TimeUnit.SECONDS);
            var secondStatus = second.get(10, TimeUnit.SECONDS);
            assertThat(List.of(firstStatus, secondStatus)).contains(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        }
        assertThat(deliver(id)).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(stock()).isEqualTo(9);
    }

    @Test
    void redeliveryAfterCommitWithoutAcknowledgementDoesNotDeductAgain() {
        long id = IDS.incrementAndGet();
        reserve(id);
        orders.createVoucherOrder(order(id)); // Commit, deliberately do not acknowledge a delivery.
        assertThat(deliver(id)).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(stock()).isEqualTo(9);
    }

    @Test
    void userLockStaysHeldWhileTheDatabaseCommitIsPaused() throws Exception {
        long id = IDS.incrementAndGet();
        var beforeCommit = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        var heldDuringCommit = new AtomicBoolean();
        // Pause the actual transaction at its commit boundary, not a mocked service return.
        doAnswer(invocation -> {
            int changed = jdbc.update("INSERT INTO tb_voucher_order(id,user_id,voucher_id) VALUES (?,?,?)", id, userId, voucherId);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    beforeCommit.countDown();
                    try {
                        if (!allowCommit.await(10, TimeUnit.SECONDS)) throw new AssertionError("commit gate timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
                @Override public void afterCommit() {
                    heldDuringCommit.set(redisson.getLock("lock:order:" + userId).isHeldByCurrentThread());
                }
            });
            return changed;
        }).when(orderMapper).insert(any(VoucherOrder.class));

        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> orders.createVoucherOrder(order(id)));
            try {
                assertThat(beforeCommit.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(orderCount()).isZero(); // Other connection cannot see the uncommitted row.
                assertThat(deliver(id)).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
            } finally {
                allowCommit.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
        }
        assertThat(heldDuringCommit).isTrue();
        assertThat(deliver(id)).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        assertThat(stock()).isEqualTo(9);
    }

    @Test
    void insertionFailureReallyRollsBackTheDatabaseStockDeduction() {
        long id = IDS.incrementAndGet();
        doThrow(new org.springframework.dao.DuplicateKeyException("fixture: insert race after stock deduction"))
                .when(orderMapper).insert(any(VoucherOrder.class));
        assertThat(deliver(id)).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        assertThat(orderCount()).isZero();
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void retryAfterRedisCompensationBeforeStatusUpdateCannotRestoreStockTwice() {
        long id = IDS.incrementAndGet();
        reserve(id);
        jdbc.update("UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=?", voucherId);
        // The Redis write succeeds, but the durable completion write fails once.
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("fixture: status update interrupted"))
                .doAnswer(invocation -> jdbc.update("UPDATE tb_voucher_order_failure SET compensation_status='done' WHERE order_id=? AND compensation_status='pending'", id))
                .when(failureMapper).update(isNull(), any(Wrapper.class));
        assertThat(deliver(id)).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        assertThat(failureStatus(id)).isEqualTo("pending");
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("10");
        assertThat(deliver(id)).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        assertThat(failureStatus(id)).isEqualTo("done");
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("10");
        assertThat(orderCount()).isZero();
    }

    @Test
    void legacyReservationCreatesADurableManualRecordWithoutChangingInventory() {
        long id = IDS.incrementAndGet();
        redis.opsForValue().set("seckill:stock:" + voucherId, "9");
        redis.opsForSet().add("seckill:order:" + voucherId, Long.toString(userId));
        jdbc.update("UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=?", voucherId);
        assertThat(deliver(id)).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        assertThat(failureStatus(id)).isEqualTo("manual");
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("9");
    }

    @Test
    void conflictingOrderIsDurablyRejectedWithoutClearingTheSuccessfulEligibility() {
        long successful = IDS.incrementAndGet();
        orders.createVoucherOrder(order(successful));
        long conflicting = IDS.incrementAndGet();
        reserve(conflicting); // Exceptional re-reservation, not normal duplicate-click behavior.
        assertThat(deliver(conflicting)).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        assertThat(failureStatus(conflicting)).isEqualTo("done");
        assertThat(orderCount()).isEqualTo(1);
        assertThat(stock()).isEqualTo(9);
        assertThat(redis.opsForSet().isMember("seckill:order:" + voucherId, Long.toString(userId))).isTrue();
        assertThat(redis.opsForHash().get("seckill:reservation:" + voucherId, Long.toString(userId)))
                .isEqualTo(Long.toString(successful));
    }

    @Test
    void realBrokerRetriesALockConflictAndEventuallyCreatesTheOrder() throws Exception {
        long id = IDS.incrementAndGet();
        reserve(id);
        var firstRetry = new CountDownLatch(1);
        doAnswer(invocation -> {
            var status = (ConsumeConcurrentlyStatus) invocation.callRealMethod();
            List<MessageExt> messages = invocation.getArgument(0);
            if (messages.stream().anyMatch(msg -> new String(msg.getBody(), StandardCharsets.UTF_8).contains(Long.toString(id)))
                    && status == ConsumeConcurrentlyStatus.RECONSUME_LATER) firstRetry.countDown();
            return status;
        }).when(consumer).consumeMessage(anyList(), any());
        var lock = redisson.getLock("lock:order:" + userId);
        lock.lock();
        try {
            var message = new VoucherOrderMessage();
            message.setOrderId(id);
            message.setUserId(userId);
            message.setVoucherId(voucherId);
            producer.sendSeckillOrder(message);
            // A fresh topic may need a route-refresh/rebalance cycle before any delivery.
            // Separate infrastructure readiness from the unchanged 15s business assertion.
            var live = (DefaultMQPushConsumer) Objects.requireNonNull(ReflectionTestUtils.getField(consumer, "consumer"));
            await().atMost(Duration.ofSeconds(45)).until(() -> live.getDefaultMQPushConsumerImpl()
                    .getRebalanceImpl().getProcessQueueTable().entrySet().stream()
                    .anyMatch(entry -> !entry.getValue().isDropped()
                            && !entry.getKey().getTopic().startsWith("%RETRY%")));
            assertThat(firstRetry.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(orderCount()).isZero();
        } finally {
            lock.unlock();
        }
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> assertThat(orderCount()).isEqualTo(1));
        assertThat(stock()).isEqualTo(9);
    }

    @Test
    void sameOrderIdWithDifferentUsersCannotCommitBothSuccessAndRejection() throws Exception {
        long id = IDS.incrementAndGet();
        long exhaustedVoucher = newVoucher(0);
        var inserted = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        doAnswer(invocation -> {
            int changed = jdbc.update("INSERT INTO tb_voucher_order(id,user_id,voucher_id) VALUES (?,?,?)", id, userId, voucherId);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    inserted.countDown();
                    try {
                        if (!allowCommit.await(10, TimeUnit.SECONDS)) throw new AssertionError("commit gate timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
            });
            return changed;
        }).when(orderMapper).insert(any(VoucherOrder.class));
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> orders.createVoucherOrder(order(id)));
            try {
                assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
                var conflicting = new VoucherOrder().setId(id).setUserId(userId + 1).setVoucherId(exhaustedVoucher);
                assertThatThrownBy(() -> orders.createVoucherOrder(conflicting)).isInstanceOf(IllegalStateException.class);
            } finally {
                allowCommit.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order_failure WHERE order_id=?", Long.class, id)).isZero();
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void callersRepeatableReadSnapshotAndRollbackCannotHideOrUndoCompensation() {
        long id = IDS.incrementAndGet();
        reserve(id);
        jdbc.update("UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=?", voucherId);
        var caller = new TransactionTemplate(transactionManager);
        assertThatCode(() -> caller.executeWithoutResult(status -> {
            // Pin the caller's MySQL snapshot before the REQUIRES_NEW rejection commits.
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order_failure WHERE order_id=?", Long.class, id)).isZero();
            orders.createVoucherOrder(order(id));
            status.setRollbackOnly();
        })).doesNotThrowAnyException();
        assertThat(failureStatus(id)).isEqualTo("done");
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("10");
    }

    @Test
    void nonCanonicalStockDuringCompensationIsDurablyMarkedManual() {
        long id = IDS.incrementAndGet();
        reserve(id);
        redis.opsForValue().set("seckill:stock:" + voucherId, "09");
        jdbc.update("UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=?", voucherId);
        assertThat(deliver(id)).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        assertThat(failureStatus(id)).isEqualTo("manual");
        assertThat(redis.opsForValue().get("seckill:stock:" + voucherId)).isEqualTo("09");
    }

    @Test
    void nonCanonicalStockIsRejectedBeforeAnyReservationMutation() {
        long id = IDS.incrementAndGet();
        redis.opsForValue().set("seckill:stock:" + voucherId, "09");
        var result = new AtomicReference<Long>();
        assertThatCode(() -> result.set(runScript("seckill.lua", id, ""))).doesNotThrowAnyException();
        assertThat(result.get()).isEqualTo(3);
        assertThat(redis.opsForSet().isMember("seckill:order:" + voucherId, Long.toString(userId))).isFalse();
    }

    @Test
    void contradictorySuccessAndFailureRecordsMustNotBeAcknowledgedAsAnOrdinaryDuplicate() {
        long id = IDS.incrementAndGet();
        orders.createVoucherOrder(order(id));
        jdbc.update("INSERT INTO tb_voucher_order_failure(order_id,user_id,voucher_id,reason,compensation_status) VALUES (?,?,?,'REJECTED_OUT_OF_STOCK','manual')", id, userId + 1, voucherId);
        assertThat(deliver(id)).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        assertThat(stock()).isEqualTo(9);
    }

    private String failureStatus(long id) {
        return jdbc.queryForObject("SELECT compensation_status FROM tb_voucher_order_failure WHERE order_id=?", String.class, id);
    }

    private ConsumeConcurrentlyStatus deliver(long id) {
        var message = new MessageExt();
        message.setMsgId("fixture-" + id);
        message.setBody(("{\"orderId\":" + id + ",\"userId\":" + userId + ",\"voucherId\":" + voucherId + "}")
                .getBytes(StandardCharsets.UTF_8));
        return consumer.consumeMessage(List.of(message), null);
    }

    private void authenticateFixtureUser() {
        var user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
    }

    private long reserve(long id) {
        redis.opsForValue().setIfAbsent("seckill:stock:" + voucherId, "10");
        return runScript("seckill.lua", id, "");
    }

    private long compensate(long id, Long retainedOrderId) {
        return runScript("seckill_rollback.lua", id, retainedOrderId == null ? "" : retainedOrderId.toString());
    }

    private long runScript(String name, long id, String retainedOrderId) {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource(name));
        script.setResultType(Long.class);
        return Objects.requireNonNull(redis.execute(script,
                List.of("seckill:stock:" + voucherId, "seckill:order:" + voucherId,
                        "seckill:reservation:" + voucherId),
                Long.toString(voucherId), Long.toString(userId), Long.toString(id),
                name.equals("seckill.lua")?"1":retainedOrderId,"1","946684800","4102444800"));
    }

    private VoucherOrder order(long id) {
        return new VoucherOrder().setId(id).setUserId(userId).setVoucherId(voucherId);
    }

    private long orderCount() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?", Long.class, voucherId));
    }

    private int stock() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, voucherId));
    }
}
