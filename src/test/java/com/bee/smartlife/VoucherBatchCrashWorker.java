package com.bee.smartlife;
import com.bee.smartlife.service.impl.VoucherOrderPersistence;
import com.bee.smartlife.entity.VoucherOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;
public class VoucherBatchCrashWorker {
 public static void main(String[] args){
   System.setProperty("spring.devtools.restart.enabled","false");
   var app=new SpringApplicationBuilder(SmartLifeApplication.class).web(WebApplicationType.NONE).run("--smartlife.batches.enabled=false","--smartlife.orders.consumer-enabled=false","--smartlife.orders.recovery-enabled=false","--smartlife.orders.lifecycle-enabled=false","--smartlife.sync.enabled=false");
   var jdbc=app.getBean(JdbcTemplate.class);long task=Long.parseLong(args[0]);
   var row=jdbc.queryForMap("SELECT r.*,b.voucher_id FROM tb_voucher_batch_row r JOIN tb_voucher_batch b ON b.id=r.task_id WHERE r.task_id=?",task);
   long order=((Number)row.get("order_id")).longValue();
   jdbc.update("UPDATE tb_voucher_batch_row SET state='PROCESSING',lease_token='crashed',lease_until='2000-01-01',last_attempt_at='2000-01-01' WHERE task_id=?",task);
   app.getBean(VoucherOrderPersistence.class).accept(new VoucherOrder().setId(order).setUserId(((Number)row.get("canonical_user_id")).longValue()).setVoucherId(((Number)row.get("voucher_id")).longValue()),row.get("request_key").toString());
   jdbc.update("UPDATE tb_voucher_order_request SET update_time='2000-01-01' WHERE order_id=?",order);
   Runtime.getRuntime().halt(74);
 }
}
