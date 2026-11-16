package com.bee.smartlife;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Synthetic paid SQL fixtures isolate settlement transactions from the purchase/MQ path. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "smartlife.orders.consumer-enabled=false","smartlife.orders.recovery-enabled=false",
    "smartlife.orders.lifecycle-enabled=false","smartlife.batches.enabled=false","smartlife.sync.enabled=false"})
@EnabledIfEnvironmentVariable(named="SMARTLIFE_INTEGRATION_TESTS",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckoutRacesIT {
  @Autowired TestRestTemplate http;
  @Autowired StringRedisTemplate redis;
  @MockitoSpyBean JdbcTemplate jdbc;
  static final AtomicLong IDS=new AtomicLong(8_700_000_000_000L+System.currentTimeMillis()%100_000_000);
  long user,merchant,admin,shop,voucher,order;
  String token,merchantToken,adminToken;
  final List<String> tokens=new ArrayList<>();

  @BeforeAll void migration() throws Exception {
    try(var c=jdbc.getDataSource().getConnection()) {
      var migration=new ClassPathResource("db/migrations/2026-11-15-checkout.sql");
      ScriptUtils.executeSqlScript(c,migration);
      ScriptUtils.executeSqlScript(c,migration);
    }
  }

  @BeforeEach void fixture() {
    user=IDS.incrementAndGet();merchant=IDS.incrementAndGet();admin=IDS.incrementAndGet();
    shop=IDS.incrementAndGet();voucher=IDS.incrementAndGet();order=IDS.incrementAndGet();
    for(long id:List.of(user,merchant,admin)) jdbc.update("INSERT INTO tb_user(id,phone,nick_name) VALUES (?,?,'checkout race fixture')",id,"16"+String.format("%09d",id%1_000_000_000));
    jdbc.update("INSERT INTO tb_shop(id,name,type_id,images,address,x,y,sold,comments,score) VALUES (?,'race shop',1,'','synthetic race fixture',113,23,0,0,45)",shop);
    jdbc.update("INSERT INTO tb_shop_permission(user_id,shop_id,role) VALUES (?,?,'OWNER')",merchant,shop);
    jdbc.update("INSERT INTO tb_shop_permission(user_id,shop_id,role) VALUES (?,0,'ADMIN')",admin);
    jdbc.update("INSERT INTO tb_voucher(id,shop_id,title,pay_value,actual_value,type,status,checkout_enabled,checkout_threshold,checkout_stack_merchant,checkout_stack_platform) VALUES (?,?,'paid race voucher',8000,10000,1,1,1,0,1,1)",voucher,shop);
    jdbc.update("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES (?,7,'2000-01-01','2100-01-01')",voucher);
    jdbc.update("INSERT INTO tb_voucher_order(id,user_id,voucher_id,status,pay_time) VALUES (?,?,?,2,UTC_TIMESTAMP())",order,user,voucher);
    jdbc.update("INSERT INTO tb_order_lifecycle(order_id,snapshot_valid,shop_id,shop_name,title,pay_value,actual_value,pay_deadline,use_begin_time,use_end_time,checkout_enabled,checkout_threshold,checkout_stack_merchant,checkout_stack_platform) VALUES (?,1,?,'race shop','paid race voucher',8000,10000,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY),1,0,1,1)",order,shop);
    token=session(user);merchantToken=session(merchant);adminToken=session(admin);
  }

  @AfterEach void cleanup() {
    reset(jdbc);
    jdbc.update("DELETE r FROM tb_checkout_reversal r JOIN tb_checkout_bill b ON b.id=r.bill_id WHERE b.shop_id=?",shop);
    jdbc.update("DELETE u FROM tb_checkout_voucher_use u JOIN tb_checkout_bill b ON b.id=u.bill_id WHERE b.shop_id=?",shop);
    jdbc.update("DELETE FROM tb_checkout_bill WHERE shop_id=?",shop);
    jdbc.update("DELETE FROM tb_checkout_coupon WHERE shop_id=?",shop);
    jdbc.update("DELETE FROM tb_checkout_promotion WHERE shop_id=?",shop);
    jdbc.update("DELETE FROM tb_order_stock_return WHERE order_id=?",order);
    jdbc.update("DELETE FROM tb_order_lifecycle WHERE order_id=?",order);
    jdbc.update("DELETE FROM tb_voucher_order WHERE id=?",order);
    jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id=?",voucher);
    jdbc.update("DELETE FROM tb_voucher WHERE id=?",voucher);
    jdbc.update("DELETE FROM tb_shop_permission WHERE user_id IN (?,?)",merchant,admin);
    jdbc.update("DELETE FROM tb_shop WHERE id=?",shop);
    jdbc.update("DELETE FROM tb_user WHERE id IN (?,?,?)",user,merchant,admin);
    tokens.forEach(t->redis.delete("login:token:"+t));tokens.clear();
  }

  String session(long id) {
    String t=UUID.randomUUID().toString();tokens.add(t);
    redis.opsForHash().putAll("login:token:"+t,Map.of("id",Long.toString(id),"nickName","checkout race fixture"));return t;
  }
  ResponseEntity<JsonNode> request(String who,HttpMethod method,String path,Object body) {
    var headers=new HttpHeaders();headers.set("authorization",who);headers.setContentType(MediaType.APPLICATION_JSON);
    return http.exchange("/checkout"+path,method,new HttpEntity<>(body,headers),JsonNode.class);
  }
  JsonNode ok(String who,HttpMethod method,String path,Object body) {
    var result=request(who,method,path,body);
    assertThat(result.getStatusCode().is2xxSuccessful()).as("%s %s: %s",method,path,result.getBody()).isTrue();
    assertThat(result.getBody().path("success").asBoolean()).isTrue();return result.getBody().path("data");
  }
  String confirmed() {
    String id=ok(token,HttpMethod.POST,"/bills",Map.of("shopId",Long.toString(shop),"total",18000,"excluded",2000,"requestKey",UUID.randomUUID().toString())).path("id").asText();
    ok(merchantToken,HttpMethod.POST,"/merchant/bills/"+id+"/confirm",Map.of("total",18000,"excluded",2000));return id;
  }
  String quote(String bill,Long paidVoucher,String platform) {
    var input=new LinkedHashMap<String,Object>();input.put("automatic",false);
    if(paidVoucher!=null)input.put("voucherId",paidVoucher.toString());
    if(platform!=null)input.put("platformId",platform);
    return ok(token,HttpMethod.POST,"/bills/"+bill+"/quote",input).path("quote").path("token").asText();
  }
  ResponseEntity<JsonNode> pay(String bill,String quote) { return request(token,HttpMethod.POST,"/bills/"+bill+"/pay",Map.of("token",quote)); }
  String state(String bill) {return jdbc.queryForObject("SELECT state FROM tb_checkout_bill WHERE id=?",String.class,bill);}
  int orderStatus() {return jdbc.queryForObject("SELECT status FROM tb_voucher_order WHERE id=?",Integer.class,order);}
  void unchangedStock() { assertThat(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?",Integer.class,voucher)).isEqualTo(7); }
  void singleBinding(String bill) {
    assertThat(orderStatus()).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT bill_id FROM tb_checkout_voucher_use WHERE order_id=?",Long.class,order)).isEqualTo(Long.parseLong(bill));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_checkout_voucher_use WHERE order_id=?",Integer.class,order)).isEqualTo(1);
    unchangedStock();
  }

  List<ResponseEntity<JsonNode>> race(String first,String firstQuote,String second,String secondQuote) throws Exception {
    var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
    try {
      Callable<ResponseEntity<JsonNode>> a=()->{ready.countDown();assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();return pay(first,firstQuote);};
      Callable<ResponseEntity<JsonNode>> b=()->{ready.countDown();assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();return pay(second,secondQuote);};
      var fa=pool.submit(a);var fb=pool.submit(b);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
      return List.of(fa.get(20,TimeUnit.SECONDS),fb.get(20,TimeUnit.SECONDS));
    } finally { start.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(20,TimeUnit.SECONDS)).isTrue(); }
  }
  void oneWinner(List<ResponseEntity<JsonNode>> results,String first,String second) {
    assertThat(results.stream().filter(r->r.getStatusCode().is2xxSuccessful()).count()).as("responses %s",results).isEqualTo(1);
    assertThat(results.stream().filter(r->r.getStatusCode().is4xxClientError()).count()).as("responses %s",results).isEqualTo(1);
    assertThat(List.of(state(first),state(second))).containsExactlyInAnyOrder("PAID","CONFIRMED");
  }

  @Test void twoBillsCannotConsumeTheSamePaidVoucher() throws Exception {
    String a=confirmed(),b=confirmed(),qa=quote(a,order,null),qb=quote(b,order,null);
    oneWinner(race(a,qa,b,qb),a,b);
    singleBinding("PAID".equals(state(a))?a:b);
  }

  String platformCoupon() {
    var body=new LinkedHashMap<String,Object>();body.put("shopId",Long.toString(shop));body.put("title","race platform grant");body.put("scope","PLATFORM");body.put("kind","FULL_REDUCTION");
    body.put("threshold",0);body.put("reduction",1000);body.put("payableBps",0);body.put("cap",0);body.put("priority",0);body.put("stackMerchant",true);body.put("stackVoucher",true);body.put("stackPlatform",true);body.put("active",true);body.put("beginTime","2000-01-01T00:00:00Z");body.put("endTime","2100-01-01T00:00:00Z");
    String rule=ok(adminToken,HttpMethod.POST,"/rules",body).path("id").asText();
    return ok(adminToken,HttpMethod.POST,"/coupons",Map.of("ruleId",rule,"userId",Long.toString(user),"requestKey",UUID.randomUUID().toString())).path("id").asText();
  }
  @Test void twoBillsCannotConsumeTheSamePlatformGrant() throws Exception {
    String coupon=platformCoupon(),a=confirmed(),b=confirmed(),qa=quote(a,null,coupon),qb=quote(b,null,coupon);
    oneWinner(race(a,qa,b,qb),a,b);
    assertThat(jdbc.queryForObject("SELECT used_bill_id FROM tb_checkout_coupon WHERE id=?",Long.class,coupon)).isEqualTo(Long.parseLong("PAID".equals(state(a))?a:b));
    assertThat(orderStatus()).isEqualTo(2);unchangedStock();
  }

  @Test void reversedVoucherCanBeReusedAndOldReplaysCannotUndoNewUse() {
    String a=confirmed(),qa=quote(a,order,null);
    assertThat(pay(a,qa).getStatusCode().is2xxSuccessful()).isTrue();singleBinding(a);
    ok(merchantToken,HttpMethod.POST,"/merchant/bills/"+a+"/reverse",Map.of("reason","synthetic full reversal"));
    assertThat(orderStatus()).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_checkout_voucher_use WHERE order_id=?",Integer.class,order)).isZero();
    String b=confirmed(),qb=quote(b,order,null);assertThat(pay(b,qb).getStatusCode().is2xxSuccessful()).isTrue();singleBinding(b);
    assertThat(pay(a,qa).getBody().path("data").path("state").asText()).isEqualTo("REVERSED");
    ok(merchantToken,HttpMethod.POST,"/merchant/bills/"+a+"/reverse",Map.of("reason","lost response replay"));
    singleBinding(b);assertThat(state(b)).isEqualTo("PAID");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_checkout_reversal WHERE bill_id=?",Integer.class,a)).isEqualTo(1);
  }

  @Test void expiryChangedDuringVoucherLockWaitIsRecheckedAfterAcquiringLock() throws Exception {
    String bill=confirmed(),quote=quote(bill,order,null);
    var attemptingLock=new CountDownLatch(1);
    doAnswer(invocation->{attemptingLock.countDown();return invocation.callRealMethod();})
        .when(jdbc).queryForList(eq("SELECT id FROM tb_voucher_order WHERE id=? FOR UPDATE"),any(Object[].class));
    var pool=Executors.newSingleThreadExecutor();Future<ResponseEntity<JsonNode>> future=null;
    try(Connection lock=jdbc.getDataSource().getConnection()) {
      lock.setAutoCommit(false);
      try {
        try(var statement=lock.prepareStatement("SELECT id FROM tb_voucher_order WHERE id=? FOR UPDATE")) {statement.setLong(1,order);try(var rows=statement.executeQuery()){assertThat(rows.next()).isTrue();}}
        future=pool.submit(()->pay(bill,quote));
        assertThat(attemptingLock.await(10,TimeUnit.SECONDS)).isTrue();
        final Future<ResponseEntity<JsonNode>> blocked=future;
        assertThatThrownBy(()->blocked.get(250,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        try(var statement=lock.prepareStatement("UPDATE tb_order_lifecycle SET use_end_time=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE order_id=?")) {statement.setLong(1,order);assertThat(statement.executeUpdate()).isEqualTo(1);}
        lock.commit();
      } finally {lock.rollback();}
      var rejected=future.get(20,TimeUnit.SECONDS);
      assertThat(rejected.getStatusCode().is4xxClientError()).as("%s",rejected.getBody()).isTrue();
      assertThat(state(bill)).isEqualTo("CONFIRMED");assertThat(orderStatus()).isEqualTo(2);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_checkout_voucher_use WHERE order_id=?",Integer.class,order)).isZero();unchangedStock();
    } finally {pool.shutdownNow();assertThat(pool.awaitTermination(20,TimeUnit.SECONDS)).isTrue();}
  }

  @Test void failureAfterEntitlementUpdatesRollsBackAndOriginalQuoteCanRetry() {
    String coupon=platformCoupon(),bill=confirmed(),quote=quote(bill,order,coupon);
    var injected=new AtomicBoolean();
    doAnswer(invocation->{
      // This executes inside the real HTTP service transaction after both entitlement writes.
      assertThat(orderStatus()).isEqualTo(3);
      assertThat(jdbc.queryForObject("SELECT used_bill_id FROM tb_checkout_coupon WHERE id=?",Long.class,coupon)).isEqualTo(Long.parseLong(bill));
      injected.set(true);throw new IllegalStateException("synthetic failure before bill settlement commit");
    }).when(jdbc).update(startsWith("UPDATE tb_checkout_bill SET state='PAID'"),any(Object[].class));
    try {
      var rejected=pay(bill,quote);assertThat(injected).isTrue();
      // Existing RuntimeException advice uses HTTP200 + success=false; the failure body is authoritative.
      assertThat(rejected.getBody().path("success").asBoolean()).isFalse();
      assertThat(rejected.getBody().path("errorMsg").asText()).isEqualTo("服务器异常");
      assertThat(state(bill)).isEqualTo("CONFIRMED");assertThat(orderStatus()).isEqualTo(2);
      assertThat(jdbc.queryForObject("SELECT used_bill_id FROM tb_checkout_coupon WHERE id=?",Long.class,coupon)).isNull();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_checkout_voucher_use WHERE order_id=?",Integer.class,order)).isZero();unchangedStock();
    } finally {reset(jdbc);}
    assertThat(pay(bill,quote).getStatusCode().is2xxSuccessful()).isTrue();singleBinding(bill);
  }
}
