package com.bee.smartlife;

import com.bee.smartlife.dto.UserDTO;
import com.bee.smartlife.entity.Voucher;
import com.bee.smartlife.entity.VoucherOrder;
import com.bee.smartlife.mapper.VoucherMapper;
import com.bee.smartlife.mq.VoucherOrderMQProducer;
import com.bee.smartlife.service.IVoucherOrderService;
import com.bee.smartlife.service.impl.VoucherOrderPersistence;
import com.bee.smartlife.service.impl.VoucherOrderReservation;
import com.bee.smartlife.service.impl.VoucherPublication;
import com.bee.smartlife.service.impl.VoucherStockPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import com.bee.smartlife.utils.UserHolder;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"smartlife.orders.consumer-enabled=false","smartlife.orders.recovery-enabled=false","smartlife.sync.enabled=false"})
@EnabledIfEnvironmentVariable(named="SMARTLIFE_INTEGRATION_TESTS",matches="true")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class P1VoucherIT {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired IVoucherOrderService orders;
    @Autowired VoucherOrderPersistence persistence;
    @Autowired VoucherOrderReservation reservation;
    @MockitoBean VoucherOrderMQProducer producer;
    @MockitoSpyBean VoucherMapper vouchers;
    @MockitoSpyBean VoucherStockPublisher stockPublisher;
    @Autowired VoucherPublication publication;
    long shopId,userId;String token;List<Long> ids=new ArrayList<>();
    LocalDateTime now(){return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));}
    @BeforeEach void fixture() throws Exception {
        userId=insert("INSERT INTO tb_user(phone,nick_name) VALUES ('136"+ThreadLocalRandom.current().nextLong(10000000,99999999)+"','voucher fixture')");
        shopId=insert("INSERT INTO tb_shop(name,type_id,images,address,x,y,sold,comments,score) VALUES ('voucher shop',1,'','synthetic',113,23,0,0,45)");
        jdbc.update("INSERT INTO tb_shop_permission(user_id,shop_id,role) VALUES (?,?,'OWNER')",userId,shopId);
        token=UUID.randomUUID().toString();redis.opsForHash().putAll("login:token:"+token,Map.of("id",Long.toString(userId),"nickName","voucher fixture"));redis.expire("login:token:"+token,Duration.ofMinutes(10));
        var sent=new SendResult();sent.setSendStatus(SendStatus.SEND_OK);when(producer.sendSeckillOrder(any())).thenReturn(sent);
    }
    @AfterEach void cleanup() {
        ids.addAll(jdbc.queryForList("SELECT id FROM tb_voucher WHERE shop_id=?",Long.class,shopId));
        for(long id:new HashSet<>(ids)) {
            for(String table:List.of("tb_voucher_order_recovery","tb_voucher_order_request","tb_voucher_order_failure","tb_voucher_order","tb_seckill_voucher"))jdbc.update("DELETE FROM "+table+" WHERE voucher_id=?",id);
            if(table("tb_voucher_publication"))jdbc.update("DELETE FROM tb_voucher_publication WHERE voucher_id=?",id);
            jdbc.update("DELETE FROM tb_voucher WHERE id=?",id);redis.delete(List.of("seckill:stock:"+id,"seckill:order:"+id,"seckill:reservation:"+id));
        }
        ids.clear();jdbc.update("DELETE FROM tb_shop_permission WHERE user_id=?",userId);jdbc.update("DELETE FROM tb_shop WHERE id=?",shopId);jdbc.update("DELETE FROM tb_user WHERE id=?",userId);redis.delete("login:token:"+token);UserHolder.removeUser();
    }
    @Test void futureActivityCannotReserveOrCreateAnOrder() {assertUnavailable(create(1,now().plusHours(2),now().plusHours(3)));}
    @Test void expiredActivityCannotReserveOrCreateAnOrder() {assertUnavailable(create(1,now().minusHours(3),now().minusHours(2)));}
    @Test void offShelfActivityCannotReserveOrCreateAnOrder() {assertUnavailable(create(2,now().minusHours(1),now().plusHours(1)));}
    @Test void aValidOwnedReservationCanStillFinishAfterExpiryAndDownShelf() {
        long id=create(1,now().minusHours(1),now().plusHours(1));
        JsonNode accepted=claim(id,UUID.randomUUID().toString());assertThat(accepted.path("success").asBoolean()).isTrue();
        long orderId=accepted.path("data").asLong();
        jdbc.update("UPDATE tb_seckill_voucher SET end_time=? WHERE voucher_id=?",now().minusMinutes(1),id);jdbc.update("UPDATE tb_voucher SET status=2 WHERE id=?",id);
        assertThat(orders.createVoucherOrder(order(orderId,id)).isRejected()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE id=?",Long.class,orderId)).isEqualTo(1);
        assertThat(redis.opsForValue().get("seckill:stock:"+id)).isEqualTo("0");
    }
    @Test void preparingRequestThatAlreadyReservedMustNotBeRejectedByTodaysExpiry() {
        long id=create(1,now().minusHours(1),now().plusHours(1));
        String key=UUID.randomUUID().toString();long orderId=ThreadLocalRandom.current().nextLong(6_000_000_000_000L,7_000_000_000_000L);
        var order=order(orderId,id);persistence.accept(order,key);assertThat(reservation.reserve(order)).isZero();
        jdbc.update("UPDATE tb_seckill_voucher SET end_time=? WHERE voucher_id=?",now().minusMinutes(1),id);
        assertThat(claim(id,key).path("data").asText()).isEqualTo(Long.toString(orderId));
        assertThat(redis.opsForValue().get("seckill:stock:"+id)).isEqualTo("0");
    }
    @Test void aFailedSqlCommitCannotLeavePublishedRedisStock() {
        AtomicLong attempted=new AtomicLong();
        doAnswer(invocation->{
            Voucher v=invocation.getArgument(0);var key=new GeneratedKeyHolder();
            int changed=jdbc.update(c->{var s=c.prepareStatement("INSERT INTO tb_voucher(shop_id,title,pay_value,actual_value,type,status) VALUES (?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);s.setLong(1,v.getShopId());s.setString(2,v.getTitle());s.setLong(3,v.getPayValue());s.setLong(4,v.getActualValue());s.setInt(5,v.getType());s.setInt(6,v.getStatus());return s;},key);
            long id=Objects.requireNonNull(key.getKey()).longValue();v.setId(id);attempted.set(id);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){throw new IllegalStateException("fixture commit failure");}});
            return changed;
        }).when(vouchers).insert(any(Voucher.class));
        JsonNode response=publish(1,now().minusHours(1),now().plusHours(1));long id=attempted.get();ids.add(id);
        assertThat(id).isPositive();assertThat(response.path("success").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher WHERE id=?",Long.class,id)).isZero();
        assertThat(redis.hasKey("seckill:stock:"+id)).isFalse();
    }
    @Test void temporaryRedisFailureLeavesDurablePendingAndAJobCanPublishWithoutResettingStock() {
        doThrow(new RedisConnectionFailureException("fixture Redis unavailable")).when(stockPublisher).publish(anyLong(),anyBoolean(),anyInt());
        long id=create(1,now().minusHours(1),now().plusHours(1));
        assertThat(jdbc.queryForObject("SELECT state FROM tb_voucher_publication WHERE voucher_id=?",String.class,id)).isEqualTo("PENDING");
        assertThat(redis.hasKey("seckill:stock:"+id)).isFalse();
        assertThat(claim(id,UUID.randomUUID().toString()).path("success").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT compensation_status FROM tb_voucher_order_failure WHERE voucher_id=?",String.class,id)).isEqualTo("done");
        doCallRealMethod().when(stockPublisher).publish(anyLong(),anyBoolean(),anyInt());
        jdbc.update("UPDATE tb_voucher_publication SET last_attempt_at=DATE_SUB(NOW(),INTERVAL 20 MINUTE) WHERE voucher_id=?",id);
        publication.retryPending(500);
        assertThat(jdbc.queryForObject("SELECT state FROM tb_voucher_publication WHERE voucher_id=?",String.class,id)).isEqualTo("READY");
        assertThat(claim(id,UUID.randomUUID().toString()).path("success").asBoolean()).isTrue();
        assertThat(redis.opsForValue().get("seckill:stock:"+id)).isEqualTo("0");
        assertThat(publication.tryPublish(id)).isTrue();
        assertThat(redis.opsForValue().get("seckill:stock:"+id)).isEqualTo("0");
    }
    @Test void lostPublicationAcknowledgementCanReplayWithoutOverwritingAnExistingCounter() {
        doAnswer(invocation->{invocation.callRealMethod();throw new RedisConnectionFailureException("fixture response lost after Redis commit");}).when(stockPublisher).publish(anyLong(),anyBoolean(),anyInt());
        long id=create(1,now().minusHours(1),now().plusHours(1));
        assertThat(redis.opsForValue().get("seckill:stock:"+id)).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT state FROM tb_voucher_publication WHERE voucher_id=?",String.class,id)).isEqualTo("PENDING");
        redis.opsForValue().set("seckill:stock:"+id,"0");
        doCallRealMethod().when(stockPublisher).publish(anyLong(),anyBoolean(),anyInt());
        assertThat(publication.tryPublish(id)).isTrue();
        assertThat(redis.opsForValue().get("seckill:stock:"+id)).isEqualTo("0");
    }
    @Test void readyActivityWithLostStockIsNotReportedReadyOrReseeded() {
        long id=create(1,now().minusHours(1),now().plusHours(1));redis.delete("seckill:stock:"+id);
        assertThat(publication.tryPublish(id)).isFalse();
        assertThat(redis.hasKey("seckill:stock:"+id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT state FROM tb_voucher_publication WHERE voucher_id=?",String.class,id)).isEqualTo("REVIEW");
    }
    @Test void legacyPublicationCannotSeedMissingStock() {
        long id=create(1,now().minusHours(1),now().plusHours(1));
        jdbc.update("UPDATE tb_voucher_publication SET state='PENDING',version=version+1,may_seed_stock=0 WHERE voucher_id=?",id);redis.delete("seckill:stock:"+id);
        assertThat(publication.tryPublish(id)).isFalse();assertThat(redis.hasKey("seckill:stock:"+id)).isFalse();
    }
    @Test void statusChangeUsesPersistedShopOwnershipAndPreservesReservedStock() {
        long id=create(1,now().minusHours(1),now().plusHours(1));
        assertThat(claim(id,UUID.randomUUID().toString()).path("success").asBoolean()).isTrue();
        var down=http.exchange("/voucher/"+id+"/status",HttpMethod.PATCH,new HttpEntity<>(Map.of("status",2),headers()),JsonNode.class);
        assertThat(down.getBody().path("success").asBoolean()).isTrue();
        assertThat(redis.opsForValue().get("seckill:stock:"+id)).isEqualTo("0");
        jdbc.update("UPDATE tb_voucher SET shop_id=2 WHERE id=?",id);
        assertThat(http.exchange("/voucher/"+id+"/status",HttpMethod.PATCH,new HttpEntity<>(Map.of("status",1,"shopId",shopId),headers()),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT status FROM tb_voucher WHERE id=?",Integer.class,id)).isEqualTo(2);
    }
    @Test void actualLuaIncludesTheStartAndExcludesTheEndUsingRedisClock() throws Exception {
        long id=create(1,now().minusHours(1),now().plusHours(1));
        String source=new String(new ClassPathResource("seckill.lua").getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        var script=new DefaultRedisScript<Long>();script.setResultType(Long.class);
        List<String> keys=List.of("seckill:stock:"+id,"seckill:order:"+id,"seckill:reservation:"+id);
        script.setScriptText("local t=redis.call('TIME');ARGV[6]=t[1]-100;ARGV[7]=t[1];\n"+source);
        assertThat(redis.execute(script,keys,Long.toString(id),Long.toString(userId),"boundary-order","1","1","0","0")).isEqualTo(8);
        assertThat(redis.opsForValue().get(keys.getFirst())).isEqualTo("1");
        script.setScriptText("local t=redis.call('TIME');ARGV[6]=t[1];ARGV[7]=t[1]+3600;\n"+source);
        assertThat(redis.execute(script,keys,Long.toString(id),Long.toString(userId),"boundary-order","1","1","0","0")).isZero();
    }
    @Test void actualRestartRecoversAProcessExitAfterSqlCommitBeforeRedisPublication() throws Exception {crashAndRestart("BEFORE_REDIS");}
    @Test void actualRestartRecoversAProcessExitAfterRedisCommitBeforeSqlAcknowledgement() throws Exception {crashAndRestart("AFTER_REDIS");}
    void crashAndRestart(String phase) throws Exception {
        String title="publication-crash-"+UUID.randomUUID();
        Path crashed=Path.of("target",title+"-crash.log").toAbsolutePath();
        runChild(phase,title,crashed,74);
        long id=jdbc.queryForObject("SELECT id FROM tb_voucher WHERE title=?",Long.class,title);ids.add(id);
        assertThat(jdbc.queryForObject("SELECT state FROM tb_voucher_publication WHERE voucher_id=?",String.class,id)).isEqualTo("PENDING");
        assertThat(redis.opsForValue().get("seckill:stock:"+id)).isEqualTo(phase.equals("BEFORE_REDIS")?null:"1");
        Path restarted=Path.of("target",title+"-restart.log").toAbsolutePath();runChild("RECOVER",title,restarted,0);
        assertThat(Files.readString(restarted)).contains("PUBLICATION_RECOVERED");
        assertThat(redis.opsForValue().get("seckill:stock:"+id)).isEqualTo("1");
        assertThat(claim(id,UUID.randomUUID().toString()).path("success").asBoolean()).isTrue();
        assertThat(redis.opsForValue().get("seckill:stock:"+id)).isEqualTo("0");
        assertThat(publication.tryPublish(id)).isTrue();assertThat(redis.opsForValue().get("seckill:stock:"+id)).isEqualTo("0");
    }
    void runChild(String phase,String title,Path log,int expectedExit) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var process=new ProcessBuilder(java,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                VoucherPublicationCrashWorker.class.getName(),phase,Long.toString(userId),Long.toString(shopId),title).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertThat(process.waitFor(120,TimeUnit.SECONDS)).as("child log %s",log).isTrue();assertThat(process.exitValue()).as("child log: %s",Files.readString(log)).isEqualTo(expectedExit);}
        finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
    }
    void assertUnavailable(long id) {
        JsonNode response=claim(id,UUID.randomUUID().toString());
        assertThat(response.path("success").asBoolean()).as("invalid activity response %s",response).isFalse();
        assertThat(redis.opsForValue().get("seckill:stock:"+id)).isEqualTo("1");
        assertThat(redis.opsForSet().isMember("seckill:order:"+id,Long.toString(userId))).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?",Long.class,id)).isZero();
    }
    long create(int status,LocalDateTime begin,LocalDateTime end){var response=publish(status,begin,end);assertThat(response.path("success").asBoolean()).as("publish %s",response).isTrue();long id=response.path("data").asLong();ids.add(id);return id;}
    JsonNode publish(int status,LocalDateTime begin,LocalDateTime end){return http.exchange("/voucher/seckill",HttpMethod.POST,new HttpEntity<>(Map.of("shopId",shopId,"title","P1 activity","payValue",100,"actualValue",200,"status",status,"stock",1,"beginTime",begin.toString(),"endTime",end.toString()),headers()),JsonNode.class).getBody();}
    JsonNode claim(long id,String key){var h=headers();h.set("Idempotency-Key",key);return http.exchange("/voucher-order/seckill/"+id,HttpMethod.POST,new HttpEntity<>(h),JsonNode.class).getBody();}
    HttpHeaders headers(){var h=new HttpHeaders();h.set("authorization",token);return h;}
    VoucherOrder order(long order,long voucher){return new VoucherOrder().setId(order).setUserId(userId).setVoucherId(voucher);}
    long insert(String sql){var key=new GeneratedKeyHolder();jdbc.update(c->c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS),key);return Objects.requireNonNull(key.getKey()).longValue();}
    boolean table(String name){return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",Integer.class,name)>0;}
}
