package com.bee.smartlife;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"smartlife.batches.enabled=false","smartlife.orders.recovery-enabled=false","smartlife.orders.lifecycle-enabled=false","smartlife.sync.enabled=false"})
@EnabledIfEnvironmentVariable(named="SMARTLIFE_INTEGRATION_TESTS",matches="true")
class VoucherBatchIT {
 @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
 @Autowired com.bee.smartlife.service.impl.VoucherBatchService batches;
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.bee.smartlife.mq.VoucherOrderMQProducer producer;
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.bee.smartlife.service.impl.VoucherOrderServiceImpl orderService;
 static final java.util.concurrent.atomic.AtomicLong IDS=new java.util.concurrent.atomic.AtomicLong(9_000_000_000_000L+System.currentTimeMillis()%100000000);
 @BeforeEach void migrate() throws Exception {try(var c=jdbc.getDataSource().getConnection()){org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.ClassPathResource("db/migrations/2026-11-13-voucher-batches.sql"));}}
 @Autowired TestRestTemplate http;
 @Autowired StringRedisTemplate redis;
 @Test void staleImportPreservesItsPrefixAndFailsWithoutDispatch(){
   long task=IDS.addAndGet(1000),order=IDS.incrementAndGet();
   try{
     jdbc.update("INSERT INTO tb_voucher_batch(id,voucher_id,shop_id,creator_id,request_key,payload_hash,state,created_at) VALUES (?,1,1,1,?,'interrupted','IMPORTING','2000-01-01')",task,UUID.randomUUID().toString());
     jdbc.update("INSERT INTO tb_voucher_batch_row(task_id,source_row,user_text,canonical_user_id,order_id,request_key,state) VALUES (?,2,'1',1,?,?,'READY')",task,order,"batch_"+task+"_2");batches.recover();
     assertThat(jdbc.queryForObject("SELECT state FROM tb_voucher_batch WHERE id=?",String.class,task)).isEqualTo("IMPORT_FAILED");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_batch_row WHERE task_id=?",Integer.class,task)).isEqualTo(1);
     assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order_request WHERE order_id=?",Integer.class,order)).isZero();
   }finally{jdbc.update("DELETE FROM tb_voucher_batch_row WHERE task_id=?",task);jdbc.update("DELETE FROM tb_voucher_batch WHERE id=?",task);}
 }
 @Test void persistedOldestAttemptFairnessReachesLaterRowsBeyondCooldownCapacity(){
   long task=IDS.addAndGet(1000),voucher=IDS.incrementAndGet();
   jdbc.update("INSERT INTO tb_voucher_batch(id,voucher_id,shop_id,creator_id,request_key,payload_hash,state) VALUES (?,?,1,1,?,'fairness','READY')",task,voucher,UUID.randomUUID().toString());
   var rows=new ArrayList<Object[]>();var requests=new ArrayList<Object[]>();
   for(int i=1;i<=401;i++){long order=IDS.incrementAndGet();rows.add(new Object[]{task,i,""+(task+i),task+i,order,"fair_"+order,i<=400?"PROCESSING":"READY"});if(i<=400)requests.add(new Object[]{order,task+i,voucher,"fair_"+order});}
   try{
     jdbc.batchUpdate("INSERT INTO tb_voucher_batch_row(task_id,source_row,user_text,canonical_user_id,order_id,request_key,state) VALUES (?,?,?,?,?,?,?)",rows);
     jdbc.batchUpdate("INSERT INTO tb_voucher_order_request(order_id,user_id,voucher_id,request_key,state,attempts) VALUES (?,?,?,?,'PREPARING',0)",requests);
     org.mockito.Mockito.doThrow(new IllegalStateException("poisoned accepted request")).when(orderService).recoverOrder(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
     for(int i=0;i<5;i++){batches.recover();jdbc.update("UPDATE tb_voucher_batch_row SET last_attempt_at=DATE_SUB(last_attempt_at,INTERVAL 10 SECOND) WHERE task_id=? AND source_row<=400",task);}
     assertThat(jdbc.queryForObject("SELECT state FROM tb_voucher_batch_row WHERE task_id=? AND source_row=401",String.class,task)).isEqualTo("FAILED");
     assertThat(jdbc.queryForObject("SELECT attempts FROM tb_voucher_batch_row WHERE task_id=? AND source_row=401",Integer.class,task)).isEqualTo(1);
     assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_batch_row WHERE task_id=? AND state='PROCESSING'",Integer.class,task)).isEqualTo(400);
   }finally{jdbc.update("DELETE FROM tb_voucher_batch_row WHERE task_id=?",task);jdbc.update("DELETE FROM tb_voucher_batch WHERE id=?",task);jdbc.update("DELETE FROM tb_voucher_order_request WHERE voucher_id=?",voucher);}
 }
 @Test void mixedRecipientsReplayAndConcurrentRecoveryProduceOneUnpaidOrder() throws Exception {
   long user=IDS.incrementAndGet(),shop=IDS.incrementAndGet(),voucher=IDS.incrementAndGet();String token=UUID.randomUUID().toString(),key=UUID.randomUUID().toString();
   jdbc.update("INSERT INTO tb_user(id,phone,nick_name) VALUES (?,?,'batch recipient')",user,"b"+(user%1000000000));
   jdbc.update("INSERT INTO tb_shop(id,name,type_id,images,address,x,y,sold,comments,score) VALUES (?,'batch shop',1,'','synthetic',113,23,0,0,45)",shop);
   jdbc.update("INSERT INTO tb_shop_permission(user_id,shop_id,role) VALUES (?,?,'OWNER')",user,shop);
   jdbc.update("INSERT INTO tb_voucher(id,shop_id,title,pay_value,actual_value,type,status) VALUES (?,?,'batch title',100,200,1,1)",voucher,shop);
   jdbc.update("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES (?,3,'2000-01-01','2100-01-01')",voucher);
   jdbc.update("INSERT INTO tb_voucher_publication(voucher_id,initial_stock,state,version,published_version) VALUES (?,3,'READY',1,1)",voucher);
   redis.opsForValue().set("seckill:stock:"+voucher,"3");redis.opsForHash().putAll("login:token:"+token,Map.of("id",Long.toString(user),"nickName","batch fixture"));
   var h=new HttpHeaders();h.set("authorization",token);long task=0;
   try {
     var body=Map.of("voucherId",Long.toString(voucher),"requestKey",key,"userIds",List.of(Long.toString(user),"bad",Long.toString(user),Long.toString(user+99999)));
     var r=http.exchange("/voucher/batches",HttpMethod.POST,new HttpEntity<>(body,h),JsonNode.class);
     assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);var data=r.getBody().path("data");task=data.path("id").asLong();assertThat(task).isPositive();
     assertThat(data.path("failed").asInt()).isEqualTo(2);assertThat(data.path("ready").asInt()).isEqualTo(2);
     // Complete structural validation precedes any task creation or dispatch.
     int beforeImport=jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_batch WHERE creator_id=?",Integer.class,user);
     var broken=VoucherBatchXlsxParserTest.workbook(w->{var s=w.createSheet();s.createRow(0).createCell(0).setCellValue("userId");for(int i=1;i<=101;i++)s.createRow(i).createCell(0).setCellValue(Long.toString(user));s.createRow(102).createCell(1).setCellValue("late invalid column");});
     var form=new org.springframework.util.LinkedMultiValueMap<String,Object>();form.add("voucherId",Long.toString(voucher));form.add("requestKey",UUID.randomUUID().toString());form.add("file",new org.springframework.core.io.ByteArrayResource(broken.getBytes()){@Override public String getFilename(){return "users.xlsx";}});
     var multipart=new HttpHeaders();multipart.putAll(h);multipart.setContentType(MediaType.MULTIPART_FORM_DATA);
     assertThat(http.exchange("/voucher/batches/import",HttpMethod.POST,new HttpEntity<>(form,multipart),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
     assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_batch WHERE creator_id=? AND state='IMPORT_FAILED'",Integer.class,user)).isEqualTo(1);
     assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_batch_row r JOIN tb_voucher_batch b ON b.id=r.task_id WHERE b.creator_id=? AND b.state='IMPORT_FAILED'",Integer.class,user)).isEqualTo(100);
     var valid=VoucherBatchXlsxParserTest.workbook(w->{var s=w.createSheet();s.createRow(0).createCell(0).setCellValue("userId");s.createRow(1).createCell(0).setCellValue("invalid user");});
     form.set("requestKey",UUID.randomUUID().toString());
     form.set("file",new org.springframework.core.io.ByteArrayResource(valid.getBytes()){@Override public String getFilename(){return "users.xlsx";}});
     var imported=http.exchange("/voucher/batches/import",HttpMethod.POST,new HttpEntity<>(form,multipart),JsonNode.class);assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(imported.getBody().path("data").path("total").asInt()).isEqualTo(1);assertThat(imported.getBody().path("data").path("failed").asInt()).isEqualTo(1);
     assertThat(http.exchange("/voucher/batches",HttpMethod.POST,new HttpEntity<>(body,h),JsonNode.class).getBody().path("data").path("id").asLong()).isEqualTo(task);
     var pool=java.util.concurrent.Executors.newFixedThreadPool(2);try{var a=pool.submit(batches::recover);var b=pool.submit(batches::recover);a.get();b.get();}finally{pool.shutdownNow();}
     long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
     while(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?",Integer.class,voucher)==0&&System.nanoTime()<until)Thread.sleep(100);
     assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?",Integer.class,voucher)).isEqualTo(1);
     assertThat(jdbc.queryForObject("SELECT status FROM tb_voucher_order WHERE voucher_id=?",Integer.class,voucher)).isEqualTo(1);
     jdbc.update("UPDATE tb_voucher_batch_row SET last_attempt_at='2000-01-01' WHERE task_id=?",task);batches.recover();
     var detail=http.exchange("/voucher/batches/"+task+"?limit=2",HttpMethod.GET,new HttpEntity<>(h),JsonNode.class).getBody().path("data");
     assertThat(detail.path("task").path("succeeded").asInt()).isEqualTo(1);assertThat(detail.path("task").path("failed").asInt()).isEqualTo(3);assertThat(detail.path("hasMore").asBoolean()).isTrue();
     assertThat(detail.path("items").get(0).path("rowNumber").asInt()).isEqualTo(1);assertThat(detail.path("nextRow").asInt()).isEqualTo(2);
     var next=http.exchange("/voucher/batches/"+task+"?beforeRow=2&limit=2",HttpMethod.GET,new HttpEntity<>(h),JsonNode.class).getBody().path("data");assertThat(next.path("items").get(0).path("rowNumber").asInt()).isEqualTo(3);
     http.exchange("/voucher/batches/"+task+"/retry",HttpMethod.POST,new HttpEntity<>(h),JsonNode.class);batches.recover();
     assertThat(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?",Integer.class,voucher)).isEqualTo(2);assertThat(redis.opsForValue().get("seckill:stock:"+voucher)).isEqualTo("2");
     redis.opsForHash().put("login:token:"+token,"id",Long.toString(user+999));
     assertThat(http.exchange("/voucher/batches/"+task,HttpMethod.GET,new HttpEntity<>(h),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
     redis.opsForHash().put("login:token:"+token,"id",Long.toString(user));
     // An unconfirmed external send is accepted intent, not a retryable row failure.
     long recipient=user+1;jdbc.update("INSERT INTO tb_user(id,phone,nick_name) VALUES (?,?,'uncertain recipient')",recipient,"b"+(recipient%1000000000));
     org.mockito.Mockito.doThrow(new IllegalStateException("uncertain external send")).doCallRealMethod().when(producer).sendSeckillOrder(org.mockito.ArgumentMatchers.any());
     long uncertain=http.exchange("/voucher/batches",HttpMethod.POST,new HttpEntity<>(Map.of("voucherId",Long.toString(voucher),"requestKey",UUID.randomUUID().toString(),"userIds",List.of(Long.toString(recipient))),h),JsonNode.class).getBody().path("data").path("id").asLong();
     batches.recover();
     var pending=jdbc.queryForMap("SELECT order_id,state,retryable FROM tb_voucher_batch_row WHERE task_id=?",uncertain);long stable=((Number)pending.get("order_id")).longValue();assertThat(pending.get("state")).isEqualTo("PROCESSING");assertThat(pending.get("retryable")).isEqualTo(false);
     http.exchange("/voucher/batches/"+uncertain+"/retry",HttpMethod.POST,new HttpEntity<>(h),JsonNode.class);
     assertThat(jdbc.queryForObject("SELECT state FROM tb_voucher_order_request WHERE order_id=?",String.class,stable)).isEqualTo("PENDING");
     jdbc.update("UPDATE tb_voucher_order_request SET update_time='2000-01-01' WHERE order_id=?",stable);jdbc.update("UPDATE tb_voucher_batch_row SET last_attempt_at='2000-01-01' WHERE task_id=?",uncertain);batches.recover();
     until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);while(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE id=?",Integer.class,stable)==0&&System.nanoTime()<until)Thread.sleep(100);
     assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE id=?",Integer.class,stable)).isEqualTo(1);
     // A failure before acceptance retries the committed identity, rather than allocating again.
     long third=user+2;jdbc.update("INSERT INTO tb_user(id,phone,nick_name) VALUES (?,?,'transient recipient')",third,"b"+(third%1000000000));
     org.mockito.Mockito.doThrow(new IllegalStateException("preaccept transient")).doCallRealMethod().when(orderService).orderForRecipient(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString());
     long transientTask=http.exchange("/voucher/batches",HttpMethod.POST,new HttpEntity<>(Map.of("voucherId",Long.toString(voucher),"requestKey",UUID.randomUUID().toString(),"userIds",List.of(Long.toString(third))),h),JsonNode.class).getBody().path("data").path("id").asLong();batches.recover();
     var failure=jdbc.queryForMap("SELECT order_id,state,retryable FROM tb_voucher_batch_row WHERE task_id=?",transientTask);assertThat(failure.get("state")).isEqualTo("FAILED");assertThat(failure.get("retryable")).isEqualTo(true);
     http.exchange("/voucher/batches/"+transientTask+"/retry",HttpMethod.POST,new HttpEntity<>(h),JsonNode.class);
     var log=java.nio.file.Path.of("target/batch-crash-"+transientTask+".log");String executable=java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
     var child=new ProcessBuilder(executable,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),VoucherBatchCrashWorker.class.getName(),Long.toString(transientTask)).redirectErrorStream(true).redirectOutput(log.toFile()).start();
     try{assertThat(child.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)).as("child %s",log).isTrue();assertThat(child.exitValue()).as(java.nio.file.Files.readString(log)).isEqualTo(74);}finally{if(child.isAlive())child.destroyForcibly();}
     assertThat(jdbc.queryForObject("SELECT state FROM tb_voucher_order_request WHERE order_id=?",String.class,((Number)failure.get("order_id")).longValue())).isEqualTo("PREPARING");batches.recover();
     assertThat(jdbc.queryForObject("SELECT order_id FROM tb_voucher_batch_row WHERE task_id=?",Long.class,transientTask)).isEqualTo(((Number)failure.get("order_id")).longValue());
     until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);while(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?",Integer.class,voucher)<3&&System.nanoTime()<until)Thread.sleep(100);
     assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?",Integer.class,voucher)).isEqualTo(3);
   }finally{
     jdbc.update("DELETE r FROM tb_voucher_batch_row r JOIN tb_voucher_batch b ON b.id=r.task_id WHERE b.creator_id=?",user);jdbc.update("DELETE FROM tb_voucher_batch WHERE creator_id=?",user);
     jdbc.update("DELETE l FROM tb_order_lifecycle l JOIN tb_voucher_order o ON o.id=l.order_id WHERE o.voucher_id=?",voucher);
     for(String table:List.of("tb_voucher_order_request","tb_voucher_order_failure","tb_voucher_order_recovery","tb_voucher_order","tb_voucher_publication","tb_seckill_voucher"))jdbc.update("DELETE FROM "+table+" WHERE voucher_id=?",voucher);
     jdbc.update("DELETE FROM tb_voucher WHERE id=?",voucher);jdbc.update("DELETE FROM tb_shop_permission WHERE user_id=?",user);jdbc.update("DELETE FROM tb_shop WHERE id=?",shop);jdbc.update("DELETE FROM tb_user WHERE id BETWEEN ? AND ?",user,user+2);redis.delete(List.of("login:token:"+token,"seckill:stock:"+voucher,"seckill:order:"+voucher,"seckill:reservation:"+voucher));
   }
 }
 @Test void authenticatedBatchListExposesDurablePageContract() {
   String token=UUID.randomUUID().toString();
   redis.opsForHash().putAll("login:token:"+token,Map.of("id","999999991","nickName","batch fixture"));
   try {
     var h=new HttpHeaders();h.set("authorization",token);
     var r=http.exchange("/voucher/batches",HttpMethod.GET,new HttpEntity<>(h),JsonNode.class);
     assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
     assertThat(r.getBody().path("data").path("items").isArray()).isTrue();
   } finally {redis.delete("login:token:"+token);}
 }
}
