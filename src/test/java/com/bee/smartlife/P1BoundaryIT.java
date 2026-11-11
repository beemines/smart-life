package com.bee.smartlife;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.bee.smartlife.service.impl.UploadedImageService;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"smartlife.orders.consumer-enabled=false", "smartlife.orders.recovery-enabled=false", "smartlife.sync.enabled=false"})
@EnabledIfEnvironmentVariable(named="SMARTLIFE_INTEGRATION_TESTS", matches="true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class P1BoundaryIT {
    static final Path UPLOADS = Path.of("target", "p1-boundary-uploads").toAbsolutePath();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) { r.add("smartlife.upload-dir", UPLOADS::toString); }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UploadedImageService imageService;
    long shopId, userId, otherId;
    String token, otherToken;

    @BeforeEach void fixture() {
        userId = user(); otherId = user();
        token = session(userId); otherToken = session(otherId);
        shopId = insert("INSERT INTO tb_shop(name,type_id,images,address,x,y,sold,comments,score) VALUES ('P1 owned fixture',1,'','synthetic',113,23,0,0,45)");
    }
    @AfterEach void cleanup() throws Exception {
        if (table("tb_uploaded_file")) {
            for (String name : jdbc.queryForList("SELECT name FROM tb_uploaded_file WHERE user_id IN (?,?)", String.class,userId,otherId)) {
                Files.deleteIfExists(UPLOADS.resolve(name.substring(1)));
            }
            jdbc.update("DELETE FROM tb_uploaded_file WHERE user_id IN (?,?)",userId,otherId);
        }
        for (String table : List.of("tb_shop_cache_sync","tb_shop_permission")) if(table(table)) jdbc.update("DELETE FROM "+table+" WHERE shop_id=?",shopId);
        for (String table : List.of("tb_voucher_publication")) if(table(table)) jdbc.update("DELETE p FROM "+table+" p JOIN tb_voucher v ON v.id=p.voucher_id WHERE v.shop_id=?",shopId);
        jdbc.update("DELETE sv FROM tb_seckill_voucher sv JOIN tb_voucher v ON v.id=sv.voucher_id WHERE v.shop_id=?",shopId);
        jdbc.update("DELETE FROM tb_voucher WHERE shop_id=?",shopId);
        jdbc.update("DELETE FROM tb_shop WHERE id=?",shopId);
        if(table("tb_shop_permission")) jdbc.update("DELETE FROM tb_shop_permission WHERE user_id IN (?,?)",userId,otherId);
        jdbc.update("DELETE FROM tb_user WHERE id IN (?,?)",userId,otherId);
        redis.delete(List.of("login:token:"+token,"login:token:"+otherToken,"cache:shop:"+shopId));
    }
    @Test void anonymousShopUpdateCannotChangeTheDatabase() {
        var r = call("/shop",HttpMethod.PUT,Map.of("id",shopId,"name","tampered"),null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("SELECT name FROM tb_shop WHERE id=?",String.class,shopId)).isEqualTo("P1 owned fixture");
    }
    @Test void loggedInUserCannotEditAShopTheyDoNotOwn() {
        assertThat(call("/shop",HttpMethod.PUT,Map.of("id",shopId,"name","tampered"),token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT name FROM tb_shop WHERE id=?",String.class,shopId)).isEqualTo("P1 owned fixture");
    }
    @Test void anonymousUserCannotPublishAVoucher() {
        var body=Map.of("shopId",shopId,"title","P1 forbidden","payValue",100,"actualValue",200,"type",0,"status",1);
        assertThat(call("/voucher",HttpMethod.POST,body,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher WHERE shop_id=?",Long.class,shopId)).isZero();
    }
    @Test void ownerCanEditOnlyAllowedFieldsButCannotCreateShops() {
        jdbc.update("INSERT INTO tb_shop_permission(user_id,shop_id,role) VALUES (?,?,'OWNER')",userId,shopId);
        assertThat(call("/shop",HttpMethod.PUT,Map.of("id",shopId,"name","owned update","score",1,"sold",999),token).getBody().path("success").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT name FROM tb_shop WHERE id=?",String.class,shopId)).isEqualTo("owned update");
        assertThat(jdbc.queryForObject("SELECT score FROM tb_shop WHERE id=?",Integer.class,shopId)).isEqualTo(45);
        assertThat(jdbc.queryForObject("SELECT sold FROM tb_shop WHERE id=?",Integer.class,shopId)).isZero();
        assertThat(call("/shop",HttpMethod.POST,Map.of("name","not an admin"),token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void shopOwnerCanCreateANormalVoucherButAnotherUserCannot() {
        jdbc.update("INSERT INTO tb_shop_permission(user_id,shop_id,role) VALUES (?,?,'OWNER')",userId,shopId);
        var body=Map.of("shopId",shopId,"title","P1 owned voucher","payValue",100,"actualValue",200);
        assertThat(call("/voucher",HttpMethod.POST,body,token).getBody().path("success").asBoolean()).isTrue();
        assertThat(call("/voucher",HttpMethod.POST,body,otherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher WHERE shop_id=?",Long.class,shopId)).isEqualTo(1);
    }
    @Test void anonymousUploadsAreRejectedWithoutOwnershipRows() throws Exception {
        assertThat(upload("real.png","image/png",png(),null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_uploaded_file WHERE user_id=?",Long.class,userId)).isZero();
    }
    @Test void publicReadHandlersRemainAccessible() {
        assertThat(call("/shop/of/name?name=P1",HttpMethod.GET,null,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call("/voucher/list/"+shopId,HttpMethod.GET,null,null).getBody().path("success").asBoolean()).isTrue();
        assertThat(call("/user/login/",HttpMethod.POST,Map.of(),null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void textDisguisedAsPngIsRejected() {
        assertThat(upload("fake.png","image/png","not-a-real-image".getBytes(),token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void emptyImageIsRejected() {
        assertThat(upload("empty.png","image/png",new byte[0],token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void declaredMimeMustMatchTheDecodedImage() throws Exception {
        assertThat(upload("fake.png","image/jpeg",png(),token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void ownerCanDeleteTheOriginalUploadedNameAndRepeatSafely() throws Exception {
        String name=uploadName(); Path file=UPLOADS.resolve(name.substring(1));
        assertThat(file).exists();
        assertThat(call("/upload/blog?name="+name,HttpMethod.DELETE,null,token).getBody().path("success").asBoolean()).isTrue();
        assertThat(file).doesNotExist();
        assertThat(call("/upload/blog?name="+name,HttpMethod.DELETE,null,token).getBody().path("success").asBoolean()).isTrue();
    }
    @Test void getMustNotDeleteAndAnonymousOrOtherUsersCannotDelete() throws Exception {
        String name=uploadName(); Path file=UPLOADS.resolve(name.substring(1));
        assertThat(call("/upload/blog/delete?name="+name,HttpMethod.GET,null,token).getStatusCode().is4xxClientError()).isTrue();
        assertThat(file).exists();
        assertThat(call("/upload/blog?name="+name,HttpMethod.DELETE,null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call("/upload/blog?name="+name,HttpMethod.DELETE,null,otherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(file).exists();
    }
    @Test void displayUrlAndTraversalAreNotStorageNames() throws Exception {
        String name=uploadName();
        assertThat(call("/upload/blog?name=/imgs"+name,HttpMethod.DELETE,null,token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call("/upload/blog?name=../outside.png",HttpMethod.DELETE,null,token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(UPLOADS.resolve(name.substring(1))).exists();
    }
    @Test void nonActiveMetadataMustNotPermitAnUnlink() throws Exception {
        String name=uploadName();Path file=UPLOADS.resolve(name.substring(1));
        jdbc.update("UPDATE tb_uploaded_file SET state='QUARANTINED' WHERE name=?",name);
        assertThat(call("/upload/blog?name="+name,HttpMethod.DELETE,null,token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(file).exists();
    }
    @Test void linkedUploadDirectoryCannotCreateShardsOutsideTheUploadRoot() throws Exception {
        Path root=Files.createDirectories(Path.of("target","p1-linked-root-"+UUID.randomUUID()).toAbsolutePath());
        Path outside=Files.createDirectories(Path.of("target","p1-linked-target-"+UUID.randomUUID()).toAbsolutePath());
        Path link=root.resolve("blogs");
        try{Files.createSymbolicLink(link,outside);}
        catch(Exception unavailable){
            if(System.getProperty("os.name").startsWith("Windows")) {
                var junction=new ProcessBuilder("cmd","/c","mklink","/J",link.toString(),outside.toString()).redirectErrorStream(true).start();
                assertThat(junction.waitFor()).as(new String(junction.getInputStream().readAllBytes())).isZero();
            }else throw unavailable;
        }
        Object original=ReflectionTestUtils.getField(imageService,"directory");
        try {
            ReflectionTestUtils.setField(imageService,"directory",root.toString());
            assertThat(upload("real.png","image/png",png(),token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            try(var contents=Files.list(outside)){assertThat(contents.count()).isZero();}
        } finally {ReflectionTestUtils.setField(imageService,"directory",original);Files.deleteIfExists(link);}
    }
    @Test void logoutRevokesTheCurrentSessionAndIsRepeatable() {
        var first=call("/user/logout",HttpMethod.POST,null,token);
        assertThat(first.getBody().path("success").asBoolean()).isTrue();
        assertThat(call("/user/me",HttpMethod.GET,null,token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(redis.hasKey("login:token:"+token)).isFalse();
        assertThat(call("/user/logout",HttpMethod.POST,null,token).getBody().path("success").asBoolean()).isTrue();
        assertThat(call("/user/me",HttpMethod.GET,null,otherToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    String uploadName() throws Exception {
        var r=upload("real.png","image/png",png(),token);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().path("success").asBoolean()).isTrue();
        return r.getBody().path("data").asText();
    }
    ResponseEntity<JsonNode> upload(String filename,String mime,byte[] bytes,String session) {
        var partHeaders=new HttpHeaders(); partHeaders.setContentType(MediaType.parseMediaType(mime));
        var form=new LinkedMultiValueMap<String,Object>();
        form.add("file",new HttpEntity<>(new ByteArrayResource(bytes){@Override public String getFilename(){return filename;}},partHeaders));
        var headers=headers(session);headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange("/upload/blog",HttpMethod.POST,new HttpEntity<>(form,headers),JsonNode.class);
    }
    ResponseEntity<JsonNode> call(String path,HttpMethod method,Object body,String session) {
        return http.exchange(path,method,new HttpEntity<>(body,headers(session)),JsonNode.class);
    }
    HttpHeaders headers(String session){var h=new HttpHeaders();if(session!=null)h.set("authorization",session);return h;}
    static byte[] png() throws Exception {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}
    long user(){return insert("INSERT INTO tb_user(phone,nick_name) VALUES ('139"+String.format("%08d",new Random().nextInt(100000000))+"','P1 fixture')");}
    String session(long id){String key=UUID.randomUUID().toString();redis.opsForHash().putAll("login:token:"+key,Map.of("id",Long.toString(id),"nickName","P1 fixture"));redis.expire("login:token:"+key,Duration.ofMinutes(10));return key;}
    long insert(String sql){var key=new GeneratedKeyHolder();jdbc.update(c->{var s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);return s;},key);return Objects.requireNonNull(key.getKey()).longValue();}
    boolean table(String name){return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",Integer.class,name)>0;}
}
