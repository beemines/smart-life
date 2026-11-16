package com.bee.smartlife.checkout;

import static org.assertj.core.api.Assertions.*;
import com.bee.smartlife.dto.UserDTO;
import com.bee.smartlife.security.ApiException;
import com.bee.smartlife.utils.UserHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@SpringBootTest(properties={"smartlife.orders.consumer-enabled=false","smartlife.orders.recovery-enabled=false",
    "smartlife.orders.lifecycle-enabled=false","smartlife.batches.enabled=false","smartlife.sync.enabled=false"})
@EnabledIfEnvironmentVariable(named="SMARTLIFE_INTEGRATION_TESTS",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckoutRulesIT {
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    static final AtomicLong IDS = new AtomicLong(8_610_000_000_000L + System.currentTimeMillis()%100000000);
    long owner, admin, user, shop;
    CheckoutRuleService service;
    @BeforeAll void migration() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migrations/2026-11-15-checkout.sql"));
        }
    }
    @BeforeEach void fixture() {
        service=context.getBean(CheckoutRuleService.class);
        owner=IDS.incrementAndGet();admin=IDS.incrementAndGet();user=IDS.incrementAndGet();shop=IDS.incrementAndGet();
        for(long id:List.of(owner,admin,user))jdbc.update("INSERT INTO tb_user(id,phone,nick_name) VALUES (?,?,'rules fixture')",id,"16"+String.format("%09d",id%1000000000));
        jdbc.update("INSERT INTO tb_shop(id,name,type_id,images,address,x,y,sold,comments,score) VALUES (?,'rules shop',1,'','synthetic',113,23,0,0,45)",shop);
        jdbc.update("INSERT INTO tb_shop_permission(user_id,shop_id,role) VALUES (?,?,'OWNER'),(?,0,'ADMIN')",owner,shop,admin);
        as(owner);
    }
    void as(long id) { var dto=new UserDTO();dto.setId(id);UserHolder.saveUser(dto); }
    ObjectNode input(String scope) {
        return json.createObjectNode().put("shopId",Long.toString(shop)).put("title","synthetic rule").put("scope",scope)
                .put("kind","FULL_REDUCTION").put("threshold",100).put("reduction",10).put("payableBps",0).put("cap",0)
                .put("priority",0).put("stackMerchant",true).put("stackVoucher",true).put("stackPlatform",true)
                .put("beginTime",Instant.now().minusSeconds(60).toString()).put("endTime",Instant.now().plusSeconds(3600).toString()).put("active",true);
    }
    ObjectNode grant(String rule,String key) {return json.createObjectNode().put("ruleId",rule).put("userId",Long.toString(user)).put("requestKey",key);}
    @AfterEach void cleanup() {
        UserHolder.removeUser();
        jdbc.update("DELETE FROM tb_checkout_coupon WHERE shop_id=?",shop);
        jdbc.update("DELETE FROM tb_checkout_promotion WHERE shop_id=?",shop);
        jdbc.update("DELETE FROM tb_shop_permission WHERE user_id IN (?,?)",owner,admin);
        jdbc.update("DELETE FROM tb_shop WHERE id=?",shop);
        jdbc.update("DELETE FROM tb_user WHERE id IN (?,?,?)",owner,admin,user);
    }
    @Test void permissionsRestrictRuleScopeAndExposeCapabilities() {
        assertThat(service.capabilities()).containsEntry("merchant",true).containsEntry("admin",false);
        assertThatThrownBy(()->service.write(input("PLATFORM"))).isInstanceOf(ApiException.class);
        as(user);assertThatThrownBy(()->service.write(input("MERCHANT"))).isInstanceOf(ApiException.class);
        as(admin);assertThat(service.capabilities()).containsEntry("admin",true);
        assertThat(service.write(input("PLATFORM"))).containsEntry("scope","PLATFORM");
    }
    @Test void updateVersionsAndImmutableScopeAreEnforced() {
        var body=input("MERCHANT");var first=service.write(body);body.put("id",first.get("id").toString()).put("reduction",20);
        assertThat(((Number)service.write(body).get("version")).longValue()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT checkout_rule_version FROM tb_shop WHERE id=?",Long.class,shop)).isEqualTo(2L);
        as(admin);body.put("scope","PLATFORM");assertThatThrownBy(()->service.write(body)).isInstanceOf(ApiException.class);
        assertThat(service.list(shop)).hasSize(1);
    }
    @Test void typedFieldsAndCandidateCapRejectInvalidWritesWithoutTruncation() {
        assertThatThrownBy(()->service.write(input("MERCHANT").put("reduction",0))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.write(input("MERCHANT").put("payableBps",9000))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.write(input("MERCHANT").put("stackVoucher","true"))).isInstanceOf(ApiException.class);
        for(int i=0;i<20;i++)service.write(input("MERCHANT"));
        assertThatThrownBy(()->service.write(input("MERCHANT"))).isInstanceOf(ApiException.class);
        assertThat(service.merchants(shop,Instant.now())).hasSize(20);
    }
    @Test void fullReductionRejectsDiscountOnlyCapInsteadOfIgnoringIt() {
        assertThatThrownBy(()->service.write(input("MERCHANT").put("cap",1))).isInstanceOf(ApiException.class);
        assertThat(service.list(shop)).isEmpty();
    }
    @Test void grantsKeepSnapshotAndClaimIdAndBindIdempotencyPayload() {
        as(admin);var body=input("PLATFORM");var rule=service.write(body);String id=rule.get("id").toString();
        var claim=service.grant(grant(id,"rules-grant-one"));assertThat(service.grant(grant(id,"rules-grant-one"))).isEqualTo(claim);
        assertThat(claim.get("id")).isNotEqualTo(id);assertThat(claim.get("ruleId")).isEqualTo(id);
        body.put("id",id).put("reduction",99);service.write(body);
        assertThat(service.coupons(shop,user,Instant.now())).singleElement().satisfies(c->assertThat(((Number)c.get("reduction")).longValue()).isEqualTo(10));
        String other=service.write(input("PLATFORM")).get("id").toString();
        assertThatThrownBy(()->service.grant(grant(other,"rules-grant-one"))).isInstanceOf(ApiException.class);
    }
    @Test void grantCapAndRevocationAreEnforcedAndRetryDoesNotConsumeAnotherSlot() {
        as(admin);String rule=service.write(input("PLATFORM")).get("id").toString();
        var first=service.grant(grant(rule,"rules-cap-0"));
        for(int i=1;i<20;i++)service.grant(grant(rule,"rules-cap-"+i));
        assertThat(service.grant(grant(rule,"rules-cap-0"))).isEqualTo(first);
        assertThatThrownBy(()->service.grant(grant(rule,"rules-cap-20"))).isInstanceOf(ApiException.class);
        long id=Long.parseLong(first.get("id").toString());
        service.revoke(id);service.revoke(id);assertThat(service.coupons(shop,user,Instant.now())).hasSize(19);
        assertThat(service.grant(grant(rule,"rules-cap-20"))).isNotNull();
    }
    @Test void usedCouponCannotBeRevokedAndExpiredRulesCannotBeGranted() {
        as(admin);String rule=service.write(input("PLATFORM")).get("id").toString();
        var claim=service.grant(grant(rule,"rules-used-one"));
        jdbc.update("UPDATE tb_checkout_coupon SET used_bill_id=123 WHERE id=?",claim.get("id"));
        assertThatThrownBy(()->service.revoke(Long.parseLong(claim.get("id").toString()))).isInstanceOf(ApiException.class);
        jdbc.update("UPDATE tb_checkout_promotion SET end_time=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",rule);
        assertThatThrownBy(()->service.grant(grant(rule,"rules-expired-one"))).isInstanceOf(ApiException.class);
    }
    @Test void usedUnexpiredClaimsReserveCapacityForBillReversal() {
        as(admin);String rule=service.write(input("PLATFORM")).get("id").toString();
        var first=service.grant(grant(rule,"rules-reserved-0"));
        for(int i=1;i<20;i++)service.grant(grant(rule,"rules-reserved-"+i));
        jdbc.update("UPDATE tb_checkout_coupon SET used_bill_id=123 WHERE id=?",first.get("id"));
        assertThatThrownBy(()->service.grant(grant(rule,"rules-reserved-20"))).isInstanceOf(ApiException.class);
        jdbc.update("UPDATE tb_checkout_coupon SET used_bill_id=NULL WHERE id=?",first.get("id"));
        assertThat(service.coupons(shop,user,Instant.now())).hasSize(20);
    }
}
