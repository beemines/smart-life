package com.bee.smartlife;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
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

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "smartlife.orders.consumer-enabled=false","smartlife.orders.recovery-enabled=false",
 "smartlife.orders.lifecycle-enabled=false","smartlife.batches.enabled=false","smartlife.sync.enabled=false"})
@EnabledIfEnvironmentVariable(named="SMARTLIFE_INTEGRATION_TESTS",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckoutIT {
 @Autowired TestRestTemplate http;
 @Autowired JdbcTemplate jdbc;
 @Autowired StringRedisTemplate redis;
 @Autowired com.bee.smartlife.service.impl.VoucherOrderPersistence orders;
 static final AtomicLong IDS=new AtomicLong(8_600_000_000_000L+System.currentTimeMillis()%100000000);
 long user,merchant,shop; String token,merchantToken;
 final List<String> tokens=new ArrayList<>();
 @BeforeAll void migration() throws Exception {
   var script=new ClassPathResource("db/migrations/2026-11-15-checkout.sql");
   if(script.exists())try(var c=jdbc.getDataSource().getConnection()){
     ScriptUtils.executeSqlScript(c,script); ScriptUtils.executeSqlScript(c,script);
   }
 }
 @BeforeEach void fixture(){
   user=IDS.incrementAndGet();merchant=IDS.incrementAndGet();shop=IDS.incrementAndGet();
   for(long id:List.of(user,merchant))jdbc.update("INSERT INTO tb_user(id,phone,nick_name) VALUES (?,?,'checkout fixture')",id,"15"+String.format("%09d",id%1000000000));
   jdbc.update("INSERT INTO tb_shop(id,name,type_id,images,address,x,y,sold,comments,score) VALUES (?,'checkout shop',1,'','synthetic',113,23,0,0,45)",shop);
   jdbc.update("INSERT INTO tb_shop_permission(user_id,shop_id,role) VALUES (?,?,'OWNER')",merchant,shop);
   token=session(user);merchantToken=session(merchant);
 }
 String session(long id){String t=UUID.randomUUID().toString();tokens.add(t);redis.opsForHash().putAll("login:token:"+t,Map.of("id",Long.toString(id),"nickName","synthetic checkout"));return t;}
 @AfterEach void cleanup(){
   if(table("tb_checkout_bill")){
     jdbc.update("DELETE r FROM tb_checkout_reversal r JOIN tb_checkout_bill b ON b.id=r.bill_id WHERE b.shop_id=?",shop);
     jdbc.update("DELETE u FROM tb_checkout_voucher_use u JOIN tb_checkout_bill b ON b.id=u.bill_id WHERE b.shop_id=?",shop);
     jdbc.update("DELETE FROM tb_checkout_bill WHERE shop_id=?",shop);
     jdbc.update("DELETE FROM tb_checkout_coupon WHERE shop_id=?",shop);
     jdbc.update("DELETE FROM tb_checkout_promotion WHERE shop_id=?",shop);
   }
   jdbc.update("DELETE l FROM tb_order_lifecycle l JOIN tb_voucher_order o ON o.id=l.order_id JOIN tb_voucher v ON v.id=o.voucher_id WHERE v.shop_id=?",shop);
   jdbc.update("DELETE o FROM tb_voucher_order o JOIN tb_voucher v ON v.id=o.voucher_id WHERE v.shop_id=?",shop);
   jdbc.update("DELETE s FROM tb_seckill_voucher s JOIN tb_voucher v ON v.id=s.voucher_id WHERE v.shop_id=?",shop);
   jdbc.update("DELETE FROM tb_voucher WHERE shop_id=?",shop);
   jdbc.update("DELETE FROM tb_shop_permission WHERE user_id=?",merchant);
   jdbc.update("DELETE FROM tb_shop WHERE id=?",shop);
   jdbc.update("DELETE FROM tb_user WHERE id IN (?,?)",user,merchant);
   tokens.forEach(t->redis.delete("login:token:"+t));tokens.clear();
 }
 boolean table(String name){return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",Integer.class,name)>0;}
 ResponseEntity<JsonNode> request(String who,HttpMethod method,String path,Object body){
   var h=new HttpHeaders();h.set("authorization",who);h.setContentType(MediaType.APPLICATION_JSON);
   return http.exchange("/checkout"+path,method,new HttpEntity<>(body,h),JsonNode.class);
 }
 JsonNode ok(String who,HttpMethod method,String path,Object body){var r=request(who,method,path,body);assertThat(r.getStatusCode().is2xxSuccessful()).as("%s %s: %s",method,path,r.getBody()).isTrue();assertThat(r.getBody().path("success").asBoolean()).isTrue();return r.getBody().path("data");}
 String draft(String key){return ok(token,HttpMethod.POST,"/bills",Map.of("shopId",Long.toString(shop),"total",18000,"excluded",2000,"requestKey",key)).path("id").asText();}
 String confirmed(){String id=draft(UUID.randomUUID().toString());ok(merchantToken,HttpMethod.POST,"/merchant/bills/"+id+"/confirm",Map.of("total",18000,"excluded",2000));return id;}
 @Test void createIsBoundToOwnerPayloadAndMerchantConfirmation(){
   String key=UUID.randomUUID().toString(),id=draft(key);assertThat(draft(key)).isEqualTo(id);
   assertThat(request(token,HttpMethod.POST,"/bills",Map.of("shopId",shop,"total",18001,"excluded",2000,"requestKey",key)).getStatusCode().value()).isEqualTo(409);
   assertThat(request(merchantToken,HttpMethod.GET,"/bills/"+id,null).getStatusCode().value()).isEqualTo(404);
   assertThat(request(token,HttpMethod.POST,"/merchant/bills/"+id+"/confirm",Map.of("total",1,"excluded",0)).getStatusCode().value()).isEqualTo(404);
   assertThat(request(token,HttpMethod.POST,"/bills/"+id+"/quote",Map.of("automatic",true)).getStatusCode().is4xxClientError()).isTrue();
   var view=ok(merchantToken,HttpMethod.POST,"/merchant/bills/"+id+"/confirm",Map.of("total",18000,"excluded",2000));assertThat(view.path("state").asText()).isEqualTo("CONFIRMED");
 }
 @Test void quotePayReplayAndFullReversalKeepOriginalCashSnapshot(){
   String id=confirmed();
   var q=ok(token,HttpMethod.POST,"/bills/"+id+"/quote",Map.of("automatic",true)).path("quote");
   assertThat(q.path("calculation").path("payable").asLong()).isEqualTo(18000);
   var pay=Map.of("token",q.path("token").asText());
   assertThat(ok(token,HttpMethod.POST,"/bills/"+id+"/pay",pay).path("state").asText()).isEqualTo("PAID");
   assertThat(ok(token,HttpMethod.POST,"/bills/"+id+"/pay",pay).path("settlement").path("calculation").path("payable").asLong()).isEqualTo(18000);
   var reverse=ok(merchantToken,HttpMethod.POST,"/merchant/bills/"+id+"/reverse",Map.of("reason","synthetic full reversal"));assertThat(reverse.path("state").asText()).isEqualTo("REVERSED");
   assertThat(ok(merchantToken,HttpMethod.POST,"/merchant/bills/"+id+"/reverse",Map.of("reason","retry")).path("state").asText()).isEqualTo("REVERSED");
   assertThat(ok(token,HttpMethod.POST,"/bills/"+id+"/pay",pay).path("state").asText()).isEqualTo("REVERSED");
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_checkout_reversal WHERE bill_id=?",Integer.class,id)).isEqualTo(1);
 }
 @Test void replacedQuoteAndChangedAmountCannotPayOldPrice(){
   String id=confirmed();
   String old=ok(token,HttpMethod.POST,"/bills/"+id+"/quote",Map.of("automatic",true)).path("quote").path("token").asText();
   String current=ok(token,HttpMethod.POST,"/bills/"+id+"/quote",Map.of("automatic",true)).path("quote").path("token").asText();
   assertThat(current).isNotEqualTo(old);
   assertThat(request(token,HttpMethod.POST,"/bills/"+id+"/pay",Map.of("token",old)).getStatusCode().is4xxClientError()).isTrue();
   ok(merchantToken,HttpMethod.POST,"/merchant/bills/"+id+"/confirm",Map.of("total",20000,"excluded",2000));
   assertThat(request(token,HttpMethod.POST,"/bills/"+id+"/pay",Map.of("token",current)).getStatusCode().is4xxClientError()).isTrue();
   assertThat(ok(token,HttpMethod.GET,"/bills/"+id,null).path("state").asText()).isEqualTo("CONFIRMED");
 }
 long voucher(boolean enabled){
   long v=IDS.incrementAndGet(),order=IDS.incrementAndGet();
   jdbc.update("INSERT INTO tb_voucher(id,shop_id,title,pay_value,actual_value,type,status,checkout_enabled,checkout_threshold,checkout_stack_merchant,checkout_stack_platform) VALUES (?,?,'paid 80 face 100',8000,10000,1,1,?,0,1,1)",v,shop,enabled?1:null);
   jdbc.update("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES (?,7,'2000-01-01','2100-01-01')",v);
   orders.create(new com.bee.smartlife.entity.VoucherOrder().setId(order).setUserId(user).setVoucherId(v));
   jdbc.update("UPDATE tb_voucher_order SET status=2,pay_time=UTC_TIMESTAMP(6) WHERE id=?",order);return order;
 }
 ResponseEntity<JsonNode> legacyRedeem(long order){var h=new HttpHeaders();h.set("authorization",merchantToken);return http.exchange("/voucher-order/"+order+"/redeem",HttpMethod.POST,new HttpEntity<>(h),JsonNode.class);}
 @Test void paidVoucherUsesCapturedContractAndCannotBypassBillWithLegacyRedeem(){
   long order=voucher(true);String id=confirmed();
   assertThat(legacyRedeem(order).getStatusCode().is4xxClientError()).isTrue();
   jdbc.update("UPDATE tb_voucher SET actual_value=50000,checkout_threshold=999999 WHERE shop_id=?",shop);
   var q=ok(token,HttpMethod.POST,"/bills/"+id+"/quote",Map.of("voucherId",Long.toString(order),"automatic",false)).path("quote");
   assertThat(q.path("calculation").path("voucherId").isTextual()).isTrue();
   assertThat(q.path("calculation").path("payable").asLong()).isEqualTo(8000);
   assertThat(q.path("calculation").path("previousPaid").asLong()).isEqualTo(8000);
   assertThat(q.path("calculation").path("totalCash").asLong()).isEqualTo(16000);
   ok(token,HttpMethod.POST,"/bills/"+id+"/pay",Map.of("token",q.path("token").asText()));
   assertThat(jdbc.queryForObject("SELECT status FROM tb_voucher_order WHERE id=?",Integer.class,order)).isEqualTo(3);
 }
 @Test void legacyVoucherRemainsRedeemableButCannotSilentlyAcquireCheckoutRules(){
   long order=voucher(false);String bill=confirmed();
   assertThat(request(token,HttpMethod.POST,"/bills/"+bill+"/quote",Map.of("voucherId",Long.toString(order))).getStatusCode().is4xxClientError()).isTrue();
   assertThat(legacyRedeem(order).getBody().path("success").asBoolean()).isTrue();
 }
 @Test void quoteExpiryAndRulesEpochInvalidatePaymentWithoutConsumingVoucher(){
   String id=confirmed();String token1=ok(token,HttpMethod.POST,"/bills/"+id+"/quote",Map.of("automatic",true)).path("quote").path("token").asText();
   jdbc.update("UPDATE tb_checkout_bill SET quote_json=JSON_SET(quote_json,'$.expiresAt','2000-01-01T00:00:00Z') WHERE id=?",id);
   assertThat(request(token,HttpMethod.POST,"/bills/"+id+"/pay",Map.of("token",token1)).getStatusCode().is4xxClientError()).isTrue();
   String token2=ok(token,HttpMethod.POST,"/bills/"+id+"/quote",Map.of("automatic",true)).path("quote").path("token").asText();
   jdbc.update("UPDATE tb_shop SET checkout_rule_version=checkout_rule_version+1 WHERE id=?",shop);
   assertThat(request(token,HttpMethod.POST,"/bills/"+id+"/pay",Map.of("token",token2)).getStatusCode().is4xxClientError()).isTrue();
   assertThat(ok(token,HttpMethod.GET,"/bills/"+id,null).path("state").asText()).isEqualTo("CONFIRMED");
 }
 @Test void fractionalMoneyOverflowAndPartialReversalAreRejected(){
   for(Object amount:List.of(1.5,-1,100000001,"9999999999999999999"))assertThat(request(token,HttpMethod.POST,"/bills",Map.of("shopId",shop,"total",amount,"excluded",0,"requestKey",UUID.randomUUID().toString())).getStatusCode().is4xxClientError()).isTrue();
   String id=confirmed(),q=ok(token,HttpMethod.POST,"/bills/"+id+"/quote",Map.of("automatic",true)).path("quote").path("token").asText();
   ok(token,HttpMethod.POST,"/bills/"+id+"/pay",Map.of("token",q));
   assertThat(request(merchantToken,HttpMethod.POST,"/merchant/bills/"+id+"/reverse",Map.of("reason","partial attempt","amount",100)).getStatusCode().is4xxClientError()).isTrue();
   assertThat(ok(token,HttpMethod.GET,"/bills/"+id,null).path("state").asText()).isEqualTo("PAID");
 }
 @Test void cancelledAndExpiredBillsCannotBeReconfirmedAndListingHasStableCursor(){
   String id=confirmed();jdbc.update("UPDATE tb_checkout_bill SET pay_deadline=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",id);
   assertThat(ok(token,HttpMethod.GET,"/bills/"+id,null).path("state").asText()).isEqualTo("CANCELLED");
   assertThat(request(merchantToken,HttpMethod.POST,"/merchant/bills/"+id+"/confirm",Map.of("total",1,"excluded",0)).getStatusCode().is4xxClientError()).isTrue();
   for(int i=0;i<3;i++)draft(UUID.randomUUID().toString());
   var page=ok(token,HttpMethod.GET,"/bills?limit=2",null);assertThat(page.path("items").size()).isEqualTo(2);assertThat(page.path("hasMore").asBoolean()).isTrue();
   var next=ok(token,HttpMethod.GET,"/bills?limit=2&beforeId="+page.path("nextId").asText()+"&beforeTime="+page.path("nextTime").asText(),null);
   assertThat(next.path("items").size()).isEqualTo(2);
   assertThat(next.path("items").get(0).path("id").asText()).isNotEqualTo(page.path("items").get(1).path("id").asText());
   assertThat(request(token,HttpMethod.GET,"/bills?limit=100",null).getStatusCode().is4xxClientError()).isTrue();
 }
 @Test void merchantPermissionIsRecheckedAfterDraftCreation(){
   String id=draft(UUID.randomUUID().toString());jdbc.update("DELETE FROM tb_shop_permission WHERE user_id=?",merchant);
   assertThat(request(merchantToken,HttpMethod.GET,"/merchant/bills/"+id,null).getStatusCode().value()).isEqualTo(404);
 }
 @Test void foreignAndMissingVoucherIdsMustHaveTheSameNonDisclosingResponse(){
   long order=voucher(true);jdbc.update("UPDATE tb_voucher_order SET user_id=? WHERE id=?",merchant,order);
   String bill=confirmed();
   var foreign=request(token,HttpMethod.POST,"/bills/"+bill+"/quote",Map.of("voucherId",Long.toString(order)));
   var absent=request(token,HttpMethod.POST,"/bills/"+bill+"/quote",Map.of("voucherId",Long.toString(IDS.incrementAndGet())));
   assertThat(foreign.getStatusCode()).isEqualTo(absent.getStatusCode());
   assertThat(foreign.getStatusCode().value()).isEqualTo(404);
   assertThat(foreign.getBody().path("errorMsg").asText()).isEqualTo(absent.getBody().path("errorMsg").asText());
 }
 @Test void olderValidVouchersRemainReachableBehindFiftyNewerLegacyOrders(){
   long older=voucher(true);for(int i=0;i<50;i++)voucher(false);
   String bill=confirmed();var first=ok(token,HttpMethod.GET,"/bills/"+bill+"/options",null);
   assertThat(first.path("vouchers").size()).isEqualTo(50);assertThat(first.path("hasMoreVouchers").asBoolean()).isTrue();
   assertThat(first.path("nextVoucherId").isTextual()).isTrue();
   var second=ok(token,HttpMethod.GET,"/bills/"+bill+"/options?beforeVoucherId="+first.path("nextVoucherId").asText(),null);
   assertThat(second.path("vouchers").size()).isEqualTo(1);
   assertThat(second.path("vouchers").get(0).path("id").asText()).isEqualTo(Long.toString(older));
   assertThat(second.path("hasMoreVouchers").asBoolean()).isFalse();
   var q=ok(token,HttpMethod.POST,"/bills/"+bill+"/quote",Map.of("voucherId",Long.toString(older))).path("quote");
   assertThat(q.path("calculation").path("payable").asLong()).isEqualTo(8000);
 }
}
