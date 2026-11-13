package com.bee.smartlife.service.impl;

import com.bee.smartlife.controller.VoucherBatchController.Submit;
import com.bee.smartlife.security.*;
import com.bee.smartlife.utils.RedisIdWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Bounded durable work queue; database leases never span external calls. */
@Service
@RequiredArgsConstructor
public class VoucherBatchService {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;
 private final ShopPermission permissions;
 private final RedisIdWorker ids;
 private final VoucherOrderServiceImpl orders;
 private final VoucherOrderPersistence persistence;
 private final VoucherEligibility eligibility;
 @Value("${smartlife.batches.enabled:true}") private boolean enabled;
 public record Input(int rowNumber,String userId) {}
 public Map<String,Object> submit(Submit body) {
   if(body==null||body.userIds()==null||body.userIds().isEmpty()||body.userIds().size()>1000)throw ApiException.badRequest("用户数须为1至1000");
   var rows=new ArrayList<Input>();for(int i=0;i<body.userIds().size();i++)rows.add(new Input(i+1,body.userIds().get(i)));
   return create(body.voucherId(),body.requestKey(),rows);
 }
 public Map<String,Object> upload(String voucher,String key,MultipartFile file) {
   long voucherId=parseId(voucher),shop=authorizeVoucher(voucherId),creator=permissions.currentUser();
   if(key==null||!key.matches("[A-Za-z0-9_-]{16,64}"))throw ApiException.badRequest("请求标识格式不正确");
   String hash;
   try {var digest=MessageDigest.getInstance("SHA-256");digest.update(("xlsx:"+voucher+":").getBytes(StandardCharsets.UTF_8));long size=0;
     try(var in=file.getInputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if((size+=n)>5L*1024*1024)throw new java.io.IOException("size");digest.update(buffer,0,n);}}
     hash=HexFormat.of().formatHex(digest.digest());
   }catch(Exception e){throw ApiException.badRequest("Excel文件超过限制或无法读取");}
   long id=ids.nextId("batch");
   try{jdbc.update("INSERT INTO tb_voucher_batch(id,voucher_id,shop_id,creator_id,request_key,payload_hash,state) VALUES (?,?,?,?,?,?,'IMPORTING')",id,voucherId,shop,creator,key,hash);}
   catch(DuplicateKeyException race){var old=jdbc.queryForMap("SELECT id,payload_hash FROM tb_voucher_batch WHERE creator_id=? AND request_key=?",creator,key);if(!hash.equals(old.get("payload_hash")))throw ApiException.badRequest("同一请求标识不能用于其他批次内容");return task(((Number)old.get("id")).longValue());}
   var buffer=new ArrayList<Input>(100);
   try{
     new VoucherBatchXlsxParser().parse(file,row->{buffer.add(row);if(buffer.size()==100){stage(id,buffer);buffer.clear();}});
     if(!buffer.isEmpty())stage(id,buffer);
     if(jdbc.update("UPDATE tb_voucher_batch SET state='READY' WHERE id=? AND state='IMPORTING'",id)!=1)throw new IllegalStateException("import expired");
     return task(id);
   }catch(Exception e){jdbc.update("UPDATE tb_voucher_batch SET state='IMPORT_FAILED',error='Excel格式不正确或超过限制，请使用新请求重新导入' WHERE id=? AND state='IMPORTING'",id);throw ApiException.badRequest("Excel格式不正确或超过限制");}
 }
 private void stage(long task,List<Input> buffer){
   long[] orderIds=new long[buffer.size()];for(int i=0;i<orderIds.length;i++)orderIds[i]=ids.nextId("order");
   tx.executeWithoutResult(s->{
     String state=jdbc.queryForObject("SELECT state FROM tb_voucher_batch WHERE id=? FOR UPDATE",String.class,task);
     if(!"IMPORTING".equals(state))throw new IllegalStateException("import is closed");
     var seen=new HashSet<Long>();var args=new ArrayList<Object[]>();
     for(int i=0;i<buffer.size();i++){var row=buffer.get(i);Long user=validId(row.userId());String error=user==null?"用户ID格式不正确":!seen.add(user)||Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM tb_voucher_batch_row WHERE task_id=? AND canonical_user_id=?)",Boolean.class,task,user))?"重复用户":null;
       args.add(new Object[]{task,row.rowNumber(),row.userId(),error==null?user:null,orderIds[i],"batch_"+task+"_"+row.rowNumber(),error==null?"READY":"FAILED",error});
     }
     jdbc.batchUpdate("INSERT INTO tb_voucher_batch_row(task_id,source_row,user_text,canonical_user_id,order_id,request_key,state,error) VALUES (?,?,?,?,?,?,?,?)",args);
   });
 }
 private Map<String,Object> create(String voucherText,String key,List<Input> rows) {
   long voucher=parseId(voucherText),shop=authorizeVoucher(voucher),creator=permissions.currentUser();
   if(key==null||!key.matches("[A-Za-z0-9_-]{16,64}"))throw ApiException.badRequest("请求标识格式不正确");
   StringBuilder payload=new StringBuilder(voucher+";");
   for(var row:rows){if(row.userId()==null||row.userId().length()>128)throw ApiException.badRequest("用户ID过长");payload.append(row.rowNumber()).append(':').append(row.userId().length()).append(':').append(row.userId()).append(';');}
   String hash;
   try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
   final long id=ids.nextId("batch");long resultId=id;
   // Allocate outside SQL transactions: Redis may block, and must not prolong SQL locks.
   final long[] orderIds=new long[rows.size()];for(int i=0;i<orderIds.length;i++)orderIds[i]=ids.nextId("order");
   try {tx.executeWithoutResult(s->{
     jdbc.update("INSERT INTO tb_voucher_batch(id,voucher_id,shop_id,creator_id,request_key,payload_hash,state) VALUES (?,?,?,?,?,?,'READY')",id,voucher,shop,creator,key,hash);
     var seen=new HashSet<Long>();
     for(int start=0;start<rows.size();start+=100){
       var args=new ArrayList<Object[]>();
       for(int index=start;index<Math.min(start+100,rows.size());index++){var row=rows.get(index);
         Long user=validId(row.userId());String error=user==null?"用户ID格式不正确":!seen.add(user)?"重复用户":null;
         args.add(new Object[]{id,row.rowNumber(),row.userId(),error==null?user:null,orderIds[index],"batch_"+id+"_"+row.rowNumber(),error==null?"READY":"FAILED",error});
       }
       jdbc.batchUpdate("INSERT INTO tb_voucher_batch_row(task_id,source_row,user_text,canonical_user_id,order_id,request_key,state,error) VALUES (?,?,?,?,?,?,?,?)",args);
     }
   });}catch(DuplicateKeyException race){
     var old=jdbc.queryForList("SELECT id,voucher_id,payload_hash FROM tb_voucher_batch WHERE creator_id=? AND request_key=?",creator,key);
     if(old.isEmpty())throw race;var r=old.getFirst();
     if(((Number)r.get("voucher_id")).longValue()!=voucher||!hash.equals(r.get("payload_hash")))throw ApiException.badRequest("同一请求标识不能用于其他批次内容");
     resultId=((Number)r.get("id")).longValue();
   }
   return task(resultId);
 }
 private long authorizeVoucher(long voucher){
   var shops=jdbc.queryForList("SELECT shop_id FROM tb_voucher WHERE id=?",Long.class,voucher);
   if(shops.isEmpty())throw ApiException.badRequest("优惠券不存在");permissions.requireShopWrite(shops.getFirst());return shops.getFirst();
 }
 private void authorizeTask(long id){
   var shops=jdbc.queryForList("SELECT shop_id FROM tb_voucher_batch WHERE id=?",Long.class,id);
   if(shops.isEmpty())throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"批次不存在");permissions.requireShopWrite(shops.getFirst());
 }
 public Object list(Long before,int limit){
   bound(limit);long user=permissions.currentUser();
   var rows=jdbc.queryForList("SELECT b.id FROM tb_voucher_batch b WHERE b.id<? AND EXISTS(SELECT 1 FROM tb_shop_permission p WHERE p.user_id=? AND ((p.shop_id=b.shop_id AND p.role='OWNER') OR (p.shop_id=0 AND p.role='ADMIN'))) ORDER BY b.id DESC LIMIT ?",Long.class,before==null?Long.MAX_VALUE:before,user,limit+1);
   boolean more=rows.size()>limit;if(more)rows.removeLast();
   var out=new LinkedHashMap<String,Object>();out.put("items",rows.stream().map(this::task).toList());out.put("hasMore",more);out.put("nextId",more?rows.getLast().toString():null);return out;
 }
 public Object detail(long id,Integer before,int limit){
   authorizeTask(id);bound(limit);if(before!=null&&before<=0)throw ApiException.badRequest("行游标不正确");
   var rows=jdbc.queryForList("SELECT source_row,user_text,order_id,state,retryable,attempts,error FROM tb_voucher_batch_row WHERE task_id=? AND source_row>? ORDER BY source_row LIMIT ?",id,before==null?0:before,limit+1);
   boolean more=rows.size()>limit;if(more)rows.removeLast();var items=new ArrayList<Map<String,Object>>();
   for(var r:rows){var o=new LinkedHashMap<String,Object>();o.put("rowNumber",r.get("source_row"));o.put("userId",r.get("user_text"));o.put("orderId",r.get("order_id").toString());o.put("state",r.get("state"));o.put("retryable",r.get("retryable"));o.put("attempts",r.get("attempts"));o.put("error",r.get("error"));items.add(o);}
   var out=new LinkedHashMap<String,Object>();out.put("task",task(id));out.put("items",items);out.put("hasMore",more);out.put("nextRow",more?rows.getLast().get("source_row"):null);return out;
 }
 public Map<String,Object> retry(long id){authorizeTask(id);jdbc.update("UPDATE tb_voucher_batch_row r SET state='READY',retryable=0,error=NULL WHERE task_id=? AND state='FAILED' AND retryable=1 AND NOT EXISTS(SELECT 1 FROM tb_voucher_order_request q WHERE q.order_id=r.order_id)",id);return task(id);}
 private Map<String,Object> task(long id){
   return jdbc.queryForObject("SELECT b.*,COUNT(r.source_row) total,COALESCE(SUM(r.state='READY'),0) ready,COALESCE(SUM(r.state='PROCESSING'),0) processing,COALESCE(SUM(r.state='SUCCEEDED'),0) succeeded,COALESCE(SUM(r.state='FAILED'),0) failed,COALESCE(SUM(r.state='FAILED' AND r.retryable=1),0) retryable_failed FROM tb_voucher_batch b LEFT JOIN tb_voucher_batch_row r ON r.task_id=b.id WHERE b.id=? GROUP BY b.id",(rs,n)->{
     var o=new LinkedHashMap<String,Object>();for(String name:List.of("id","voucher_id","shop_id"))o.put(name.equals("voucher_id")?"voucherId":name.equals("shop_id")?"shopId":name,rs.getString(name));
     for(String name:List.of("total","ready","processing","succeeded","failed"))o.put(name,rs.getInt(name));o.put("retryableFailed",rs.getInt("retryable_failed"));
     String state=rs.getString("state");if("READY".equals(state)){state=rs.getInt("processing")>0?"RUNNING":rs.getInt("ready")>0?"READY":rs.getInt("failed")>0?"PARTIAL":"COMPLETED";}
     o.put("state",state);o.put("error",rs.getString("error"));o.put("createdAt",rs.getTimestamp("created_at").toLocalDateTime().toString());return o;
   },id);
 }
 private static void bound(int limit){if(limit<1||limit>100)throw ApiException.badRequest("分页大小须为1至100");}
 static Long validId(String s){try{if(s==null||!s.matches("[1-9][0-9]{0,18}"))return null;return Long.parseLong(s);}catch(Exception e){return null;}}
 private static long parseId(String s){Long id=validId(s);if(id==null)throw ApiException.badRequest("ID格式不正确");return id;}
 @Scheduled(fixedDelayString="${smartlife.batches.interval-ms:10000}") public void scheduled(){if(enabled)recover();}
 public void recover(){
   jdbc.update("UPDATE tb_voucher_batch SET state='IMPORT_FAILED',error='导入中断，请使用新请求重新导入' WHERE state='IMPORTING' AND created_at<DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 15 MINUTE)");
   var rows=jdbc.queryForList("SELECT r.task_id,r.source_row FROM tb_voucher_batch_row r JOIN tb_voucher_batch b ON b.id=r.task_id WHERE b.state='READY' AND r.state IN ('READY','PROCESSING') AND r.last_attempt_at<DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 30 SECOND) AND (r.lease_until IS NULL OR r.lease_until<UTC_TIMESTAMP(6)) ORDER BY r.last_attempt_at,r.task_id,r.source_row LIMIT 100");
   for(var row:rows){long task=((Number)row.get("task_id")).longValue();int number=((Number)row.get("source_row")).intValue();String token=UUID.randomUUID().toString();
     if(jdbc.update("UPDATE tb_voucher_batch_row SET lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 60 SECOND),last_attempt_at=UTC_TIMESTAMP(6),attempts=attempts+1,state='PROCESSING' WHERE task_id=? AND source_row=? AND state IN ('READY','PROCESSING') AND last_attempt_at<DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 30 SECOND) AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))",token,task,number)!=1)continue;
     try{process(task,number,token);}catch(Exception e){/* A database outage leaves the lease recoverable, never loses the identity. */}
   }
 }
 private void process(long task,int number,String token){
   var r=jdbc.queryForMap("SELECT r.*,b.voucher_id FROM tb_voucher_batch_row r JOIN tb_voucher_batch b ON b.id=r.task_id WHERE task_id=? AND source_row=?",task,number);
   long order=((Number)r.get("order_id")).longValue(),user=((Number)r.get("canonical_user_id")).longValue(),voucher=((Number)r.get("voucher_id")).longValue();
   String state="PROCESSING",error=null;boolean retry=false;
   try{
     var request=persistence.findRequest(order);
     if(request==null){
       if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM tb_user WHERE id=?)",Boolean.class,user))){finish(task,number,token,"FAILED",false,"用户不存在");return;}
       var rule=eligibility.rule(voucher);long now=Instant.now().getEpochSecond();
       if(!rule.available()||now<rule.beginSecond()||now>=rule.endSecond()){finish(task,number,token,"FAILED",false,"优惠券不可领取或不在领取时间");return;}
       orders.orderForRecipient(voucher,user,order,r.get("request_key").toString());
     }else if("PREPARING".equals(request.getState())||"PENDING".equals(request.getState()))orders.recoverOrder(order,LocalDateTime.now().minusSeconds(30));
   }catch(Exception e){error="暂时无法处理，请稍后重试";}
   var accepted=persistence.findRequest(order);
   if(accepted==null){state="FAILED";retry=true;}
   else if(persistence.findOrder(order)!=null){state="SUCCEEDED";error=null;}
   else if("REJECTED".equals(accepted.getState())||"REVIEW".equals(accepted.getState())){state="FAILED";error=accepted.getReason()==null?"订单需要人工核对":accepted.getReason();}
   finish(task,number,token,state,retry,error);
 }
 private void finish(long task,int row,String token,String state,boolean retry,String error){jdbc.update("UPDATE tb_voucher_batch_row SET state=?,retryable=?,error=?,lease_token=NULL,lease_until=NULL WHERE task_id=? AND source_row=? AND lease_token=?",state,retry,error,task,row,token);}
}
