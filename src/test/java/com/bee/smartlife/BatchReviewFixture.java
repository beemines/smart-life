package com.bee.smartlife;
import java.util.*;
import com.bee.smartlife.service.impl.*;
import com.bee.smartlife.entity.VoucherOrder;
import com.bee.smartlife.utils.UserHolder;
import com.bee.smartlife.dto.UserDTO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
final class BatchReviewFixture implements AutoCloseable {
 final JdbcTemplate jdbc;final StringRedisTemplate redis;final VoucherBatchService batches;
 final long creator,recipient,shop,voucher;
 BatchReviewFixture(JdbcTemplate jdbc,StringRedisTemplate redis,VoucherBatchService batches){
  this.jdbc=jdbc;this.redis=redis;this.batches=batches;long base=java.util.concurrent.ThreadLocalRandom.current().nextLong(7_000_000_000_000L,8_000_000_000_000L);creator=base;recipient=base+1;shop=base+2;voucher=base+3;
  for(long u:new long[]{creator,recipient})jdbc.update("INSERT INTO tb_user(id,phone,nick_name) VALUES (?,?,'batch review')",u,"w"+(u%1000000000));
  jdbc.update("INSERT INTO tb_shop(id,name,type_id,images,address,x,y,sold,comments,score) VALUES (?,'batch review',1,'','synthetic',113,23,0,0,45)",shop);
  jdbc.update("INSERT INTO tb_shop_permission(user_id,shop_id,role) VALUES (?,?,'OWNER')",creator,shop);
  jdbc.update("INSERT INTO tb_voucher(id,shop_id,title,pay_value,actual_value,type,status) VALUES (?,?,'batch review',100,200,1,1)",voucher,shop);
  jdbc.update("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES (?,10,'2000-01-01','2100-01-01')",voucher);
  jdbc.update("INSERT INTO tb_voucher_publication(voucher_id,initial_stock,state,version,published_version) VALUES (?,10,'READY',1,1)",voucher);
  redis.opsForValue().set("seckill:stock:"+voucher,"10");var dto=new UserDTO();dto.setId(creator);UserHolder.saveUser(dto);
 }
 long submit(){return Long.parseLong(batches.submit(new com.bee.smartlife.controller.VoucherBatchController.Submit(Long.toString(voucher),UUID.randomUUID().toString(),List.of(Long.toString(recipient)))).get("id").toString());}
 long order(long task){return jdbc.queryForObject("SELECT order_id FROM tb_voucher_batch_row WHERE task_id=?",Long.class,task);}
 void accept(long task,VoucherOrderPersistence persistence,long user,String state){long order=order(task);String key=jdbc.queryForObject("SELECT request_key FROM tb_voucher_batch_row WHERE task_id=?",String.class,task);persistence.accept(new VoucherOrder().setId(order).setUserId(user).setVoucherId(voucher),key);jdbc.update("UPDATE tb_voucher_order_request SET state=?,update_time='2000-01-01' WHERE order_id=?",state,order);}
 void revoke(){jdbc.update("DELETE FROM tb_shop_permission WHERE user_id=?",creator);}
 Map<String,Object> row(long task){return jdbc.queryForMap("SELECT state,retryable,error FROM tb_voucher_batch_row WHERE task_id=?",task);}
 @Override public void close(){
  UserHolder.removeUser();var orders=jdbc.queryForList("SELECT r.order_id FROM tb_voucher_batch_row r JOIN tb_voucher_batch b ON b.id=r.task_id WHERE b.creator_id=?",Long.class,creator);
  for(long order:orders){for(String table:List.of("tb_order_stock_return","tb_order_lifecycle"))jdbc.update("DELETE FROM "+table+" WHERE order_id=?",order);for(String table:List.of("tb_voucher_order_request","tb_voucher_order_failure","tb_voucher_order_recovery"))jdbc.update("DELETE FROM "+table+" WHERE order_id=?",order);jdbc.update("DELETE FROM tb_voucher_order WHERE id=?",order);}
  jdbc.update("DELETE r FROM tb_voucher_batch_row r JOIN tb_voucher_batch b ON b.id=r.task_id WHERE b.creator_id=?",creator);jdbc.update("DELETE FROM tb_voucher_batch WHERE creator_id=?",creator);
  for(String table:List.of("tb_voucher_publication","tb_seckill_voucher"))jdbc.update("DELETE FROM "+table+" WHERE voucher_id=?",voucher);jdbc.update("DELETE FROM tb_voucher WHERE id=?",voucher);jdbc.update("DELETE FROM tb_shop_permission WHERE user_id=?",creator);jdbc.update("DELETE FROM tb_shop WHERE id=?",shop);jdbc.update("DELETE FROM tb_user WHERE id IN (?,?)",creator,recipient);redis.delete(List.of("seckill:stock:"+voucher,"seckill:order:"+voucher,"seckill:reservation:"+voucher));
 }
}
