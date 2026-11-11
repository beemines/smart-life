package com.bee.smartlife;

import com.bee.smartlife.dto.UserDTO;
import com.bee.smartlife.entity.Shop;
import com.bee.smartlife.mapper.ShopMapper;
import com.bee.smartlife.service.IShopService;
import com.bee.smartlife.utils.UserHolder;
import com.bee.smartlife.service.impl.ShopCache;
import com.bee.smartlife.service.impl.ShopCacheSync;
import com.bee.smartlife.mq.ShopCacheBroadcast;
import com.github.benmanes.caffeine.cache.Cache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Statement;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"smartlife.orders.consumer-enabled=false","smartlife.orders.recovery-enabled=false","smartlife.sync.enabled=false"})
@EnabledIfEnvironmentVariable(named="SMARTLIFE_INTEGRATION_TESTS",matches="true")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ShopCacheIT {
    @Autowired IShopService shops;
    @Autowired JdbcTemplate jdbc;
    @Autowired RedissonClient redisson;
    @Autowired StringRedisTemplate redis;
    @Autowired TestRestTemplate http;
    @MockitoSpyBean ShopMapper mapper;
    @MockitoSpyBean ShopCache cache;
    @Autowired ShopCacheSync sync;
    @Autowired DefaultMQProducer producer;
    @Autowired ObjectMapper json;
    long shopId,userId;String token;List<Long> created=new ArrayList<>();
    @BeforeEach void fixture() {
        String phone="137"+ThreadLocalRandom.current().nextLong(10000000,99999999);
        userId=insert("INSERT INTO tb_user(phone,nick_name) VALUES ('"+phone+"','cache fixture')");
        shopId=insert("INSERT INTO tb_shop(name,type_id,images,address,x,y,sold,comments,score) VALUES ('cache old',1,'','synthetic',113,23,0,0,45)");
        created.add(shopId);
        jdbc.update("INSERT INTO tb_shop_permission(user_id,shop_id,role) VALUES (?,0,'ADMIN')",userId);
        token=UUID.randomUUID().toString();redis.opsForHash().putAll("login:token:"+token,Map.of("id",Long.toString(userId),"nickName","cache fixture"));redis.expire("login:token:"+token,Duration.ofMinutes(5));
        UserHolder.saveUser(user());
        redisson.<Long>getBloomFilter("bf:shop:id").add(shopId);
    }
    @AfterEach void cleanup() {
        UserHolder.removeUser();
        for(long id:created) {
            if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='tb_shop_cache_sync'",Integer.class)>0) jdbc.update("DELETE FROM tb_shop_cache_sync WHERE shop_id=?",id);
            jdbc.update("DELETE FROM tb_shop_permission WHERE shop_id=?",id);jdbc.update("DELETE FROM tb_shop WHERE id=?",id);redis.delete("cache:shop:"+id);
        }
        created.clear();jdbc.update("DELETE FROM tb_shop_permission WHERE user_id=?",userId);jdbc.update("DELETE FROM tb_user WHERE id=?",userId);redis.delete("login:token:"+token);
    }
    @Test void newlyCreatedShopIsImmediatelyQueryableWithoutRestart() {
        var headers=new HttpHeaders();headers.set("authorization",token);
        var body=Map.of("name","cache new","typeId",1,"images","","address","synthetic","x",113,"y",23);
        var response=http.exchange("/shop",HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class).getBody();
        assertThat(response.path("success").asBoolean()).isTrue();long id=response.path("data").asLong();created.add(id);
        assertThat(shops.queryById(id).getSuccess()).isTrue();
    }
    @Test void updatingAnUnknownShopDoesNotPretendToSucceed() {
        var input=new Shop();input.setId(Long.MAX_VALUE);input.setName("not there");
        assertThat(shops.update(input).getSuccess()).isFalse();
    }
    @Test void cacheMissCannotRefillOldDataBetweenInvalidationAndSqlCommit() throws Exception {
        assertThat(name(shops.queryById(shopId).getData())).isEqualTo("cache old");
        var beforeCommit=new CountDownLatch(1);var allowCommit=new CountDownLatch(1);
        doAnswer(invocation->{
            Shop input=invocation.getArgument(0);
            int changed=jdbc.update("UPDATE tb_shop SET name=? WHERE id=?",input.getName(),input.getId());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void beforeCommit(boolean readOnly){beforeCommit.countDown();try{if(!allowCommit.await(10,TimeUnit.SECONDS))throw new AssertionError("commit gate timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
            });return changed;
        }).when(mapper).updateById(any(Shop.class));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var update=pool.submit(()->{UserHolder.saveUser(user());try{var s=new Shop();s.setId(shopId);s.setName("cache new");return shops.update(s);}finally{UserHolder.removeUser();}});
            Future<?> query=null;
            try {
                assertThat(beforeCommit.await(10,TimeUnit.SECONDS)).isTrue();
                // Original implementation already invalidated its caches here;
                // a concurrent lookup could refill the old committed SQL row.
                query=pool.submit(()->shops.queryById(shopId));
                Thread.sleep(150);
            } finally {allowCommit.countDown();}
            assertThat(update.get(10,TimeUnit.SECONDS).getSuccess()).isTrue();
            if(query!=null)query.get(10,TimeUnit.SECONDS);
        }
        assertThat(name(shops.queryById(shopId).getData())).isEqualTo("cache new");
    }
    @Test void failedBloomSynchronizationStaysQueryableAndRetryableEvenWithAnEmptyRedisHit() {
        doThrow(new RedisConnectionFailureException("fixture Bloom unavailable")).when(cache).invalidateShared(any(Long.class));
        var headers=new HttpHeaders();headers.set("authorization",token);
        var body=Map.of("name","cache retry","typeId",1,"images","","address","synthetic","x",113,"y",23);
        var response=http.exchange("/shop",HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class).getBody();
        long id=response.path("data").asLong();created.add(id);
        assertThat(response.path("success").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT version>synced_version FROM tb_shop_cache_sync WHERE shop_id=?",Boolean.class,id)).isTrue();
        redis.opsForValue().set("cache:shop:"+id,"");
        assertThat(name(shops.queryById(id).getData())).isEqualTo("cache retry");
        doCallRealMethod().when(cache).invalidateShared(any(Long.class));
        await().atMost(Duration.ofSeconds(15)).until(()->sync.trySync(id));
        assertThat(jdbc.queryForObject("SELECT version=synced_version FROM tb_shop_cache_sync WHERE shop_id=?",Boolean.class,id)).isTrue();
        assertThat(shops.queryById(id).getSuccess()).isTrue();
    }
    @Test void twoIndependentLocalCachesReceiveActualBrokerBroadcastAndDuplicatesAreHarmless() throws Exception {
        // Prime the real topic before the second process-equivalent context starts.
        send(new ShopCacheSync.Change(shopId,1));
        try(var second=secondContext(true,180000)) {
            IShopService other=second.getBean(IShopService.class);
            ShopCache otherCache=second.getBean(ShopCache.class);
            assertThat(name(other.queryById(shopId).getData())).isEqualTo("cache old");
            // Establish that its real listener is pulling, without arbitrary startup sleeps.
            @SuppressWarnings("unchecked") Cache<Long,String> entries=(Cache<Long,String>)ReflectionTestUtils.getField(otherCache,"local");
            await().atMost(Duration.ofSeconds(40)).untilAsserted(()->{
                send(new ShopCacheSync.Change(shopId,1));
                assertThat(entries.getIfPresent(shopId)).isNull();
            });
            assertThat(name(other.queryById(shopId).getData())).isEqualTo("cache old");
            assertThat(name(shops.queryById(shopId).getData())).isEqualTo("cache old");
            var update=new Shop();update.setId(shopId);update.setName("broadcast new");
            assertThat(shops.update(update).getSuccess()).isTrue();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(name(other.queryById(shopId).getData())).isEqualTo("broadcast new"));
            long version=jdbc.queryForObject("SELECT version FROM tb_shop_cache_sync WHERE shop_id=?",Long.class,shopId);
            send(new ShopCacheSync.Change(shopId,version));send(new ShopCacheSync.Change(shopId,version));
            assertThat(name(other.queryById(shopId).getData())).isEqualTo("broadcast new");
            assertThat(jdbc.queryForObject("SELECT version FROM tb_shop_cache_sync WHERE shop_id=?",Long.class,shopId)).isEqualTo(version);
        }
    }
    @Test void anInstanceThatMissedNotificationsRecoversAtItsConfiguredLocalTtl() {
        try(var second=secondContext(false,2000)) {
            IShopService other=second.getBean(IShopService.class);
            assertThat(name(other.queryById(shopId).getData())).isEqualTo("cache old");
            var update=new Shop();update.setId(shopId);update.setName("ttl new");shops.update(update);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(name(other.queryById(shopId).getData())).isEqualTo("ttl new"));
        }
    }
    ConfigurableApplicationContext secondContext(boolean broadcast,long ttl) {
        System.setProperty("spring.devtools.restart.enabled","false");
        var app=new SpringApplication(SmartLifeApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        return app.run("--smartlife.orders.consumer-enabled=false","--smartlife.orders.recovery-enabled=false","--smartlife.sync.enabled=false",
                "--smartlife.cache.broadcast-enabled="+broadcast,"--smartlife.cache.local-ttl-ms="+ttl);
    }
    void send(ShopCacheSync.Change change) throws Exception {
        producer.send(new Message("smartlife-shop-cache-change",json.writeValueAsBytes(change)));
    }
    UserDTO user(){var u=new UserDTO();u.setId(userId);return u;}
    String name(Object data){return data instanceof Shop shop?shop.getName():null;}
    long insert(String sql){var key=new GeneratedKeyHolder();jdbc.update(c->c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS),key);return Objects.requireNonNull(key.getKey()).longValue();}
}
