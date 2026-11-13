package com.bee.smartlife;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
@SpringBootTest(properties={"smartlife.batches.enabled=false","smartlife.orders.consumer-enabled=false","smartlife.orders.recovery-enabled=false","smartlife.orders.lifecycle-enabled=false","smartlife.sync.enabled=false"})
@EnabledIfEnvironmentVariable(named="SMARTLIFE_INTEGRATION_TESTS",matches="true")
class OrderListPlanIT {
 @Autowired JdbcTemplate jdbc;
 @Test void reportActualMineQueriesAtTenThousandOrders()throws Exception{
   try(var c=jdbc.getDataSource().getConnection()){org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.ClassPathResource("db/migrations/2026-11-13-order-list-indexes.sql"));}
   long user=9_999_888_777L,base=8_888_000_000_000L;var args=new ArrayList<Object[]>();for(int i=0;i<10000;i++)args.add(new Object[]{base+i,user,base+i,i%1000==0?2:1});
   try{jdbc.batchUpdate("INSERT INTO tb_voucher_order(id,user_id,voucher_id,status,create_time) VALUES (?,?,?,?,DATE_SUB('2026-01-01',INTERVAL ? SECOND))",args.stream().map(a->new Object[]{a[0],a[1],a[2],a[3],((Number)a[0]).longValue()-base}).toList());
     jdbc.execute("ANALYZE TABLE tb_voucher_order");StringBuilder report=new StringBuilder();
     for(String status:List.of(""," AND o.status=2")){
       String sql=com.bee.smartlife.service.impl.OrderLifecyclePersistence.SELECT+" WHERE o.user_id="+user+status+" AND (o.create_time<'2026-01-01' OR (o.create_time='2026-01-01' AND o.id<9223372036854775807)) ORDER BY o.create_time DESC,o.id DESC LIMIT 21";
       report.append(sql).append('\n').append(jdbc.queryForList("EXPLAIN ANALYZE "+sql)).append('\n');
     }
     Files.writeString(Path.of("target/order-list-queryplan.txt"),report);assertThat(report.toString()).contains("actual time").doesNotContain("-> Sort:");
   }finally{jdbc.update("DELETE FROM tb_voucher_order WHERE user_id=?",user);}
 }
}
