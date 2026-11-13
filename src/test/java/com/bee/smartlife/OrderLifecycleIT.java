package com.bee.smartlife;

import static org.assertj.core.api.Assertions.*;

import com.bee.smartlife.dto.UserDTO;
import com.bee.smartlife.entity.VoucherOrder;
import com.bee.smartlife.service.impl.*;
import com.bee.smartlife.utils.UserHolder;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "smartlife.orders.consumer-enabled=false",
      "smartlife.orders.recovery-enabled=false",
      "smartlife.orders.lifecycle-enabled=false",
      "smartlife.sync.enabled=false"
    })
@EnabledIfEnvironmentVariable(named = "SMARTLIFE_INTEGRATION_TESTS", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderLifecycleIT {
  @Autowired TestRestTemplate http;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcTemplate jdbc;
  @Autowired VoucherOrderPersistence orders;
  @Autowired OrderLifecyclePersistence persistence;
  @Autowired OrderLifecycleService lifecycle;
  @Autowired OrderStockReturn returns;
  static final AtomicLong IDS =
      new AtomicLong(8_100_000_000_000L + System.currentTimeMillis() % 100_000_000);
  long user, shop, voucher, id;
  String token;

  @BeforeAll
  void migrate() throws Exception {
    try (var c = jdbc.getDataSource().getConnection()) {
      ScriptUtils.executeSqlScript(
          c, new ClassPathResource("db/migrations/2026-11-12-order-lifecycle.sql"));
    }
  }

  @BeforeEach
  void fixture() {
    user = IDS.incrementAndGet();
    shop = IDS.incrementAndGet();
    voucher = IDS.incrementAndGet();
    id = IDS.incrementAndGet();
    token = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO tb_shop(id,name,type_id,images,address,x,y,sold,comments,score) VALUES"
            + " (?,'snapshot shop',1,'','synthetic',113,23,0,0,45)",
        shop);
    jdbc.update(
        "INSERT INTO tb_voucher(id,shop_id,title,pay_value,actual_value,type,status) VALUES"
            + " (?,?,'snapshot title',100,200,1,1)",
        voucher,
        shop);
    jdbc.update(
        "INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES"
            + " (?,1,'2000-01-01','2100-01-01')",
        voucher);
    redis
        .opsForHash()
        .putAll(
            "login:token:" + token,
            Map.of("id", Long.toString(user), "nickName", "lifecycle fixture"));
    orders.create(new VoucherOrder().setId(id).setUserId(user).setVoucherId(voucher));
    redis.opsForValue().set("seckill:stock:" + voucher, "0");
    redis.opsForSet().add("seckill:order:" + voucher, Long.toString(user));
    redis
        .opsForHash()
        .put("seckill:reservation:" + voucher, Long.toString(user), Long.toString(id));
    var dto = new UserDTO();
    dto.setId(user);
    UserHolder.saveUser(dto);
  }

  @AfterEach
  void cleanup() {
    UserHolder.removeUser();
    for (String t : List.of("tb_order_stock_return", "tb_order_lifecycle"))
      jdbc.update("DELETE FROM " + t + " WHERE order_id=?", id);
    jdbc.update("DELETE FROM tb_shop_permission WHERE user_id=?", user);
    jdbc.update("DELETE FROM tb_voucher_order WHERE user_id=?", user);
    jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id=?", voucher);
    jdbc.update("DELETE FROM tb_voucher WHERE id=?", voucher);
    jdbc.update("DELETE FROM tb_shop WHERE id=?", shop);
    redis.delete(
        List.of(
            "login:token:" + token,
            "seckill:stock:" + voucher,
            "seckill:order:" + voucher,
            "seckill:reservation:" + voucher,
            "seckill:returned:" + id));
  }

  HttpHeaders headers() {
    var h = new HttpHeaders();
    h.set("authorization", token);
    return h;
  }

  ResponseEntity<JsonNode> get(String path) {
    return http.exchange(
        "/voucher-order/" + path, HttpMethod.GET, new HttpEntity<>(headers()), JsonNode.class);
  }

  ResponseEntity<JsonNode> post(String action) {
    return http.exchange(
        "/voucher-order/" + id + "/" + action,
        HttpMethod.POST,
        new HttpEntity<>(headers()),
        JsonNode.class);
  }

  int status() {
    return jdbc.queryForObject("SELECT status FROM tb_voucher_order WHERE id=?", Integer.class, id);
  }

  void sql(String text) {
    jdbc.update(text, id);
  }

  @Test
  void authenticatedOrderListHasStablePageContract() {
    var r = get("mine?limit=2");
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    var data = r.getBody().path("data");
    assertThat(data.path("items").isArray()).isTrue();
    assertThat(data.path("items").get(0).path("id").asText()).isEqualTo(Long.toString(id));
    assertThat(data.path("items").get(0).path("createTime").asText()).endsWith("Z");
  }

  @Test
  void snapshotsSurviveEditsAndMigrationReplay() throws Exception {
    jdbc.update("UPDATE tb_voucher SET title='edited',pay_value=999,shop_id=2 WHERE id=?", voucher);
    migrate();
    var data = get("detail/" + id).getBody().path("data");
    assertThat(data.path("title").asText()).isEqualTo("snapshot title");
    assertThat(data.path("payValue").asLong()).isEqualTo(100);
    assertThat(data.path("shopId").asText()).isEqualTo(Long.toString(shop));
    // Client money fields and later template edits cannot reprice the server-owned snapshot.
    var paid=http.exchange("/voucher-order/"+id+"/pay",HttpMethod.POST,
        new HttpEntity<>(Map.of("payValue",1,"actualValue",999999,"amount",1),headers()),JsonNode.class);
    assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(paid.getBody().path("data").path("payValue").asLong()).isEqualTo(100);
    assertThat(paid.getBody().path("data").path("actualValue").asLong()).isEqualTo(200);
  }

  @Test
  void foreignOwnerAndMerchantAreHidden() {
    redis.opsForHash().put("login:token:" + token, "id", Long.toString(user + 100));
    assertThat(get("detail/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(post("pay").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("merchant/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void cursorRequiresPairAndUsesLimitPlusOne() {
    assertThat(get("mine?beforeId=1").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("mine?limit=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    long other = IDS.incrementAndGet();
    jdbc.update(
        "INSERT INTO tb_voucher_order(id,user_id,voucher_id,create_time) SELECT"
            + " ?,user_id,voucher_id+1000,create_time FROM tb_voucher_order WHERE id=?",
        other,
        id);
    var data = get("mine?limit=1").getBody().path("data");
    assertThat(data.path("hasMore").asBoolean()).isTrue();
    var next =
        get("mine?limit=1&beforeId="
                + data.path("nextId").asText()
                + "&beforeTime="
                + data.path("nextTime").asText())
            .getBody()
            .path("data");
    assertThat(next.path("items").get(0).path("id").asText()).isEqualTo(Long.toString(id));
    assertThat(get("mine?status=2").getBody().path("data").path("items").size()).isZero();
  }

  @Test
  void payRefundReplayAfterExpiryReturnsCurrentStateAndReturnsStockOnce() {
    assertThat(post("pay").getBody().path("data").path("status").asInt()).isEqualTo(2);
    assertThat(post("refund").getBody().path("data").path("status").asInt()).isEqualTo(6);
    sql("UPDATE tb_order_lifecycle SET use_end_time=UTC_TIMESTAMP() WHERE order_id=?");
    assertThat(post("pay").getBody().path("data").path("status").asInt()).isEqualTo(6);
    assertThat(post("refund").getBody().path("data").path("status").asInt()).isEqualTo(6);
    assertStockReturned();
  }

  void assertStockReturned() {
    assertThat(
            jdbc.queryForObject(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, voucher))
        .isEqualTo(1);
    assertThat(redis.opsForValue().get("seckill:stock:" + voucher)).isEqualTo("1");
    assertThat(redis.opsForSet().isMember("seckill:order:" + voucher, Long.toString(user)))
        .isTrue();
    assertThat(redis.opsForHash().get("seckill:reservation:" + voucher, Long.toString(user)))
        .isEqualTo(Long.toString(id));
  }

  @Test
  void cancelReplayAndDuplicateCreateCannotDeductAgain() {
    assertThat(post("cancel").getBody().path("data").path("status").asInt()).isEqualTo(4);
    post("cancel");
    orders.create(new VoucherOrder().setId(id).setUserId(user).setVoucherId(voucher));
    lifecycle.recover();
    assertStockReturned();
  }

  @Test
  void cutoffPayAtDeadlineFailsAndTimeoutReturnsOnce() {
    sql("UPDATE tb_order_lifecycle SET pay_deadline=UTC_TIMESTAMP(6) WHERE order_id=?");
    assertThat(post("pay").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    lifecycle.recover();
    lifecycle.recover();
    assertThat(status()).isEqualTo(4);
    assertThat(get("detail/" + id).getBody().path("data").path("closeReason").asText())
        .isEqualTo("TIMEOUT");
    assertStockReturned();
  }

  @Test
  void earlyTimeoutDoesNothingAndAcceptedRefundFinishesAfterExpiry() {
    persistence.action(id, "timeout", true);
    assertThat(status()).isEqualTo(1);
    lifecycle.pay(id);
    persistence.action(id, "refund", false);
    sql("UPDATE tb_order_lifecycle SET use_end_time=UTC_TIMESTAMP() WHERE order_id=?");
    assertThat(status()).isEqualTo(5);
    lifecycle.recover();
    assertThat(status()).isEqualTo(6);
    assertStockReturned();
  }

  @Test
  void explicitMerchantGrantAndPersistedShopScope() {
    assertThat(get("capabilities").getBody().path("data").path("merchant").asBoolean()).isFalse();
    grant();
    assertThat(get("capabilities").getBody().path("data").path("merchant").asBoolean()).isTrue();
    lifecycle.pay(id);
    jdbc.update("UPDATE tb_voucher SET shop_id=2 WHERE id=?", voucher);
    assertThat(post("redeem").getBody().path("data").path("status").asInt()).isEqualTo(3);
    assertThat(post("pay").getBody().path("data").path("status").asInt()).isEqualTo(3);
    assertThat(post("redeem").getBody().path("data").path("status").asInt()).isEqualTo(3);
    assertThat(post("refund").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  void grant() {
    jdbc.update(
        "INSERT INTO tb_shop_permission(user_id,shop_id,role) VALUES (?,?,'OWNER')", user, shop);
  }

  @Test
  void expiredUseWindowCannotBePaidEvenBeforePaymentDeadline() {
    sql("UPDATE tb_order_lifecycle SET use_end_time=UTC_TIMESTAMP(6) WHERE order_id=?");
    assertThat(post("pay").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("detail/" + id).getBody().path("data").path("canPay").asBoolean()).isFalse();
    assertThat(status()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT pay_time FROM tb_voucher_order WHERE id=?", java.time.LocalDateTime.class, id)).isNull();
    assertThat(redis.opsForValue().get("seckill:stock:" + voucher)).isEqualTo("0");
  }

  @Test
  void futureUseWindowAllowsPrepayment() {
    sql("UPDATE tb_order_lifecycle SET use_begin_time=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 DAY),use_end_time=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 2 DAY) WHERE order_id=?");
    assertThat(get("detail/" + id).getBody().path("data").path("canPay").asBoolean()).isTrue();
    assertThat(post("pay").getBody().path("data").path("status").asInt()).isEqualTo(2);
  }

  @Test
  void useWindowStartInclusiveEndExclusive() {
    grant();
    lifecycle.pay(id);
    sql(
        "UPDATE tb_order_lifecycle SET use_begin_time=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 DAY)"
            + " WHERE order_id=?");
    assertThat(post("redeem").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    sql(
        "UPDATE tb_order_lifecycle SET use_begin_time=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1"
            + " DAY),use_end_time=UTC_TIMESTAMP() WHERE order_id=?");
    assertThat(post("redeem").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("refund").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void invalidLegacySnapshotCannotBeMadeActionableByLaterVoucherEdit() throws Exception {
    sql("DELETE FROM tb_order_lifecycle WHERE order_id=?");
    jdbc.update("DELETE FROM tb_voucher WHERE id=?", voucher);
    migrate();
    assertThat(post("pay").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    jdbc.update(
        "INSERT INTO tb_voucher(id,shop_id,title,pay_value,actual_value) VALUES"
            + " (?,?,'restored',100,200)",
        voucher,
        shop);
    migrate();
    assertThat(post("cancel").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void redisAppliedBeforeAckReplaysMarkerAfterOtherKeysDisappear() {
    persistence.action(id, "cancel", false);
    assertThat(returns.apply(id, voucher, user)).isZero();
    redis.delete(
        List.of(
            "seckill:stock:" + voucher,
            "seckill:reservation:" + voucher,
            "seckill:order:" + voucher));
    lifecycle.recover();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM tb_order_stock_return WHERE order_id=?", String.class, id))
        .isEqualTo("DONE");
    assertThat(redis.hasKey("seckill:stock:" + voucher)).isFalse();
  }

  @Test
  void corruptRedisMovesToReviewWithoutBlindReseed() {
    redis.delete("seckill:stock:" + voucher);
    post("cancel");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM tb_order_stock_return WHERE order_id=?", String.class, id))
        .isEqualTo("REVIEW");
    assertThat(redis.hasKey("seckill:stock:" + voucher)).isFalse();
  }

  @Test
  void poisonedOldestTimeoutRowsDoNotStarveLaterOrders() {
    var poisoned = new ArrayList<Long>();
    try {
      for (int i = 0; i < 100; i++) {
        long other = IDS.incrementAndGet();
        poisoned.add(other);
        jdbc.update(
            "INSERT INTO tb_voucher_order(id,user_id,voucher_id,create_time) SELECT"
                + " ?,user_id+?,voucher_id+1000,create_time FROM tb_voucher_order WHERE id=?",
            other,
            i + 1,
            id);
        jdbc.update(
            "INSERT INTO"
                + " tb_order_lifecycle(order_id,snapshot_valid,shop_id,shop_name,title,pay_value,actual_value,pay_deadline,use_begin_time,use_end_time)"
                + " SELECT"
                + " ?,1,shop_id,shop_name,title,pay_value,actual_value,'2000-01-01',use_begin_time,use_end_time"
                + " FROM tb_order_lifecycle WHERE order_id=?",
            other,
            id);
      }
      sql("UPDATE tb_order_lifecycle SET pay_deadline=UTC_TIMESTAMP(6) WHERE order_id=?");
      lifecycle.recover();
      lifecycle.recover();
      assertThat(status()).isEqualTo(4);
    } finally {
      for (long other : poisoned) {
        jdbc.update("DELETE FROM tb_order_lifecycle WHERE order_id=?", other);
        jdbc.update("DELETE FROM tb_voucher_order WHERE id=?", other);
      }
    }
  }

  @Test
  void timeoutQueueFairnessSurvivesMorePoisonThanCooldownCapacity() {
    assertRecoveryFairness("close_attempt_at", false);
  }

  @Test
  void refundQueueFairnessSurvivesMorePoisonThanCooldownCapacity() {
    assertRecoveryFairness("refund_attempt_at", true);
  }

  private void assertRecoveryFairness(String attemptColumn, boolean refund) {
    var poisoned = new ArrayList<Long>();
    try {
      for (int i = 0; i < 400; i++) {
        long other = id - 1000 - i;
        poisoned.add(other);
        jdbc.update(
            "INSERT INTO tb_voucher_order(id,user_id,voucher_id,status,create_time) SELECT"
                + " ?,user_id+?,voucher_id+1000,?,create_time FROM tb_voucher_order WHERE id=?",
            other,
            i + 1,
            refund ? 5 : 1,
            id);
        jdbc.update(
            "INSERT INTO tb_order_lifecycle(order_id,snapshot_valid,shop_id,shop_name,title,"
                + "pay_value,actual_value,pay_deadline,use_begin_time,use_end_time,close_scheduled)"
                + " SELECT ?,?,shop_id,shop_name,title,pay_value,actual_value,'2000-01-01',"
                + "use_begin_time,use_end_time,1 FROM tb_order_lifecycle WHERE order_id=?",
            other,
            refund ? 0 : 1,
            id);
      }
      if (refund) {
        persistence.action(id, "pay", false);
        persistence.action(id, "refund", false);
      } else {
        sql("UPDATE tb_order_lifecycle SET pay_deadline=UTC_TIMESTAMP(6) WHERE order_id=?");
      }
      // Model a 10-second scan cadence without sleeping through the 30-second cooldown.
      // Four hundred older poisoned rows exceed three batches of cooldown capacity.
      for (int batch = 0; batch < 5; batch++) {
        lifecycle.recover();
        for (long other : poisoned) {
          jdbc.update(
              "UPDATE tb_order_lifecycle SET "
                  + attemptColumn
                  + "=DATE_SUB("
                  + attemptColumn
                  + ",INTERVAL 10 SECOND) WHERE order_id=?",
              other);
        }
      }
      assertThat(status()).isEqualTo(refund ? 6 : 4);
      assertStockReturned();
    } finally {
      for (long other : poisoned) {
        jdbc.update("DELETE FROM tb_order_stock_return WHERE order_id=?", other);
        jdbc.update("DELETE FROM tb_order_lifecycle WHERE order_id=?", other);
        jdbc.update("DELETE FROM tb_voucher_order WHERE id=?", other);
      }
    }
  }

  @Test
  void durableCloseSchedulingAndActualDelayedDelivery() throws Exception {
    var mq = context.getBean(com.bee.smartlife.mq.OrderCloseMQ.class);
    org.springframework.test.util.ReflectionTestUtils.setField(mq, "enabled", true);
    try {
      mq.start();
      sql(
          "UPDATE tb_order_lifecycle SET pay_deadline=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 2 SECOND)"
              + " WHERE order_id=?");
      lifecycle.afterCreated(id);
      assertThat(
              jdbc.queryForObject(
                  "SELECT close_scheduled FROM tb_order_lifecycle WHERE order_id=?",
                  Integer.class,
                  id))
          .isEqualTo(1);
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (status() == 1 && System.nanoTime() < until) Thread.sleep(100);
      assertThat(status()).isEqualTo(4);
      lifecycle.recover();
      assertStockReturned();
    } finally {
      mq.stop();
      org.springframework.test.util.ReflectionTestUtils.setField(mq, "enabled", false);
    }
  }

  @Test
  void actualProcessExitAfterRedisApplyBeforeSqlAckRecoversOnRestart() throws Exception {
    persistence.action(id, "cancel", false);
    runChild("APPLY", 74);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM tb_order_stock_return WHERE order_id=?", String.class, id))
        .isEqualTo("PENDING");
    runChild("RECOVER", 0);
    assertStockReturned();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM tb_order_stock_return WHERE order_id=?", String.class, id))
        .isEqualTo("DONE");
  }

  @Autowired org.springframework.context.ApplicationContext context;

  void runChild(String phase, int exit) throws Exception {
    var log = java.nio.file.Path.of("target", "lifecycle-" + id + "-" + phase + ".log");
    var javaExe =
        java.nio.file.Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    var process =
        new ProcessBuilder(
                javaExe,
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                OrderLifecycleCrashWorker.class.getName(),
                phase,
                Long.toString(id),
                Long.toString(voucher),
                Long.toString(user))
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertThat(process.waitFor(90, TimeUnit.SECONDS)).as("child log %s", log).isTrue();
      assertThat(process.exitValue()).as("child log %s", log).isEqualTo(exit);
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  @Test
  void lifecycleRacesAreSerialized() throws Exception {
    race("pay", "cancel");
    assertThat(status()).isIn(2, 4);
    if (status() == 4) return;
    grant();
    race("refund", "redeem");
    assertThat(status()).isIn(3, 6);
  }

  void race(String a, String b) throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      var fs = new ArrayList<Future<?>>();
      for (String action : List.of(a, b))
        fs.add(
            pool.submit(
                () -> {
                  try {
                    start.await();
                    post(action);
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                }));
      start.countDown();
      for (var f : fs) f.get(20, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
  }
}
