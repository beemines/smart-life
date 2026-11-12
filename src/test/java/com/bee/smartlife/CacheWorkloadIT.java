package com.bee.smartlife;

import com.bee.smartlife.entity.Shop;
import com.bee.smartlife.service.impl.ShopCache;
import com.bee.smartlife.utils.CacheClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.nio.file.*;
import java.sql.Statement;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.awaitility.Awaitility.await;

/** A bounded, reproducible functional workload, not a production capacity claim. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
 "smartlife.orders.consumer-enabled=false","smartlife.orders.recovery-enabled=false",
 "smartlife.sync.enabled=false","smartlife.orders.lifecycle-enabled=false"})
@EnabledIfEnvironmentVariable(named="SMARTLIFE_INTEGRATION_TESTS",matches="true")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CacheWorkloadIT {
 @Autowired JdbcTemplate jdbc; @Autowired StringRedisTemplate redis; @Autowired RedissonClient redisson;
 @MockitoSpyBean ShopCache cache; @MockitoSpyBean CacheClient cacheClient;
 final List<Long> ids=new ArrayList<>(); final AtomicInteger loads=new AtomicInteger(),metadata=new AtomicInteger();
 final List<Map<String,Object>> results=new ArrayList<>();
 @BeforeEach void fixture(){
  for(int i=0;i<8;i++){
   var key=new GeneratedKeyHolder();
   jdbc.update(c->c.prepareStatement("INSERT INTO tb_shop(name,type_id,images,address,x,y,sold,comments,score) VALUES ('workload',1,'','synthetic workload',113,23,0,0,45)",Statement.RETURN_GENERATED_KEYS),key);
   long id=Objects.requireNonNull(key.getKey()).longValue();ids.add(id);redisson.<Long>getBloomFilter("bf:shop:id").add(id);
  }
  doAnswer(call->{metadata.incrementAndGet();return call.callRealMethod();}).when(cache).pending(anyLong());
 }
 @AfterEach void cleanup(){
  for(long id:ids){cache.evictLocal(id);redis.delete("cache:shop:"+id);jdbc.update("DELETE FROM tb_shop WHERE id=?",id);}
 }
 Shop read(long id){
  return cache.find(id,()->{loads.incrementAndGet();return jdbc.queryForObject("SELECT id,name FROM tb_shop WHERE id=?",(r,n)->{var s=new Shop();s.setId(r.getLong("id"));s.setName(r.getString("name"));return s;},id);});
 }
 void cold(){for(long id:ids){cache.evictLocal(id);redis.delete("cache:shop:"+id);}}
 void workload(String name,int requests,int threads,List<Long> targets) throws Exception {
  loads.set(0);metadata.set(0);var start=new CountDownLatch(1);var timings=new ConcurrentLinkedQueue<Long>();
  long wall=System.nanoTime();
  try(var pool=Executors.newFixedThreadPool(threads)){
   var futures=new ArrayList<Future<?>>();
   for(int i=0;i<requests;i++){long id=targets.get(i%targets.size());futures.add(pool.submit(()->{
    try{start.await();long t=System.nanoTime();Shop shop=read(id);timings.add(System.nanoTime()-t);assertThat(shop.getId()).isEqualTo(id);assertThat(shop.getName()).isEqualTo("workload");}
    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
   }));}
   start.countDown();for(var f:futures)f.get(30,TimeUnit.SECONDS);
  }
  var sorted=timings.stream().sorted().toList();
  results.add(Map.of("scenario",name,"requests",requests,"threads",threads,"shopSelects",loads.get(),"syncMetadataChecks",metadata.get(),"p95Ms",sorted.get((int)Math.ceil(sorted.size()*.95)-1)/1_000_000.0,"wallMs",(System.nanoTime()-wall)/1_000_000.0));
 }
 @Test void measuredHotColdExpiryAndInjectedReadFailureRemainCorrect() throws Exception {
  for(int round=0;round<2;round++){
   cold();read(ids.getFirst());workload("hot-local-"+round,128,8,List.of(ids.getFirst()));assertThat(loads).hasValue(0);
   cold();workload("cold-one-shop-"+round,64,16,List.of(ids.getFirst()));assertThat(loads).hasValue(1);
   cold();for(long id:ids){read(id);cache.evictLocal(id);redis.expire("cache:shop:"+id,Duration.ofMillis(100));}
   await().atMost(Duration.ofSeconds(5)).until(()->ids.stream().noneMatch(id->Boolean.TRUE.equals(redis.hasKey("cache:shop:"+id))));
   workload("concentrated-expiry-"+round,128,16,ids);assertThat(loads.get()).isEqualTo(ids.size());
   cold();
   doThrow(new RedisConnectionFailureException("workload injected cache-read failure")).when(cacheClient).queryWithBloomAndPassThrough(anyString(),any(),eq(Shop.class),any(),anyLong(),any(),any());
   workload("injected-cache-read-failure-"+round,32,8,List.of(ids.getFirst()));assertThat(loads).hasValue(32);
   reset(cacheClient);
  }
  Path path=Path.of("target/cache-workload.json");
  new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),Map.of("os",System.getProperty("os.name"),"java",System.getProperty("java.version"),"availableProcessors",Runtime.getRuntime().availableProcessors(),"maxHeapBytes",Runtime.getRuntime().maxMemory(),"note","Direct ShopCache workload including actual Redis locks and SQL reads, excludes HTTP; read failure injected at cache-client boundary, Redis lock service remains available. No baseline speedup claim.","results",results));
 }
}
