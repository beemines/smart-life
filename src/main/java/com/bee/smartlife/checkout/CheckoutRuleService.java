package com.bee.smartlife.checkout;

import com.bee.smartlife.security.ApiException;
import com.bee.smartlife.security.ShopPermission;
import com.bee.smartlife.service.impl.OrderLifecyclePersistence;
import com.bee.smartlife.utils.RedisIdWorker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(isolation=Isolation.READ_COMMITTED)
public class CheckoutRuleService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ShopPermission permission;
    private final RedisIdWorker ids;
    private final OrderLifecyclePersistence clock;

    public Map<String,Object> capabilities() {
        long user=permission.currentUser();
        boolean admin=exists("SELECT EXISTS(SELECT 1 FROM tb_shop_permission WHERE user_id=? AND shop_id=0 AND role='ADMIN')",user);
        return Map.of("admin",admin,"merchant",admin||exists("SELECT EXISTS(SELECT 1 FROM tb_shop_permission WHERE user_id=? AND role='OWNER')",user));
    }

    public List<Map<String,Object>> list(long shopId) {
        permission.currentUser();
        if(shopId<=0)throw ApiException.badRequest("店铺ID不正确");
        return jdbc.queryForList("SELECT * FROM tb_checkout_promotion WHERE shop_id=? ORDER BY id",shopId)
                .stream().map(this::dto).toList();
    }

    public Map<String,Object> write(JsonNode input) {
        long shop=CheckoutRequests.number(input,"shopId");
        String scope=CheckoutRequests.text(input,"scope",16);
        if("PLATFORM".equals(scope))permission.requireAdmin();
        else if("MERCHANT".equals(scope))permission.requireShopWrite(shop);
        else throw ApiException.badRequest("优惠范围不正确");
        String title=CheckoutRequests.text(input,"title",80),kind=CheckoutRequests.text(input,"kind",24);
        long threshold=amount(input,"threshold"),reduction=amount(input,"reduction"),cap=amount(input,"cap");
        long bps=CheckoutRequests.number(input,"payableBps"),priority=CheckoutRequests.number(input,"priority");
        if(bps<0||bps>9999||priority<Integer.MIN_VALUE||priority>Integer.MAX_VALUE)
            throw ApiException.badRequest("折扣或优先级超出范围");
        if(!("FULL_REDUCTION".equals(kind)&&reduction>0&&bps==0&&cap==0 || "DISCOUNT".equals(kind)&&reduction==0&&bps>=1))
            throw ApiException.badRequest("优惠类型与满减/折扣字段不匹配");
        boolean sm=CheckoutRequests.flag(input,"stackMerchant"),sv=CheckoutRequests.flag(input,"stackVoucher"),sp=CheckoutRequests.flag(input,"stackPlatform");
        boolean active=CheckoutRequests.flag(input,"active");
        Instant begin=CheckoutRequests.instant(input,"beginTime"),end=CheckoutRequests.instant(input,"endTime");
        if(!end.isAfter(begin))throw ApiException.badRequest("优惠结束时间必须晚于开始时间");
        Long existingId=CheckoutRequests.optionalId(input,"id");
        lockEpoch(shop);
        long version=1;
        if(existingId!=null) {
            Map<String,Object> old=promotion(existingId);
            if(number(old,"shop_id")!=shop||!scope.equals(old.get("scope")))throw ApiException.badRequest("不能修改优惠所属店铺或范围");
            version=Math.addExact(number(old,"version"),1);
        }
        Instant now=clock.now().toInstant(ZoneOffset.UTC);
        if(active&&end.isAfter(now)&&"MERCHANT".equals(scope)) {
            int count=jdbc.queryForObject("SELECT COUNT(*) FROM tb_checkout_promotion WHERE shop_id=? AND scope='MERCHANT' AND active=1 AND end_time>? AND id<>?",Integer.class,shop,utc(now),existingId==null?0:existingId);
            if(count>=20)throw conflict("启用门店优惠最多20项");
        }
        long id=existingId==null?ids.nextId("checkout-rule"):existingId;
        if(existingId==null)jdbc.update("INSERT INTO tb_checkout_promotion(id,shop_id,title,scope,kind,threshold,reduction,payable_bps,cap,priority,stack_merchant,stack_voucher,stack_platform,begin_time,end_time,active,version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,shop,title,scope,kind,threshold,reduction,bps,cap,priority,sm,sv,sp,utc(begin),utc(end),active,version);
        else jdbc.update("UPDATE tb_checkout_promotion SET title=?,kind=?,threshold=?,reduction=?,payable_bps=?,cap=?,priority=?,stack_merchant=?,stack_voucher=?,stack_platform=?,begin_time=?,end_time=?,active=?,version=? WHERE id=?",
                title,kind,threshold,reduction,bps,cap,priority,sm,sv,sp,utc(begin),utc(end),active,version,id);
        jdbc.update("UPDATE tb_shop SET checkout_rule_version=checkout_rule_version+1 WHERE id=?",shop);
        return dto(promotion(id));
    }

    public Map<String,Object> grant(JsonNode input) {
        permission.requireAdmin();
        long ruleId=CheckoutRequests.number(input,"ruleId"),user=CheckoutRequests.number(input,"userId");
        String key=CheckoutRequests.key(input);
        // Serializes grant caps and idempotency without reversing payment's coupon -> shop lock order.
        if(jdbc.queryForList("SELECT id FROM tb_user WHERE id=? FOR UPDATE",user).isEmpty())throw missing("用户不存在");
        var prior=jdbc.queryForList("SELECT * FROM tb_checkout_coupon WHERE owner_user_id=? AND request_key=?",user,key);
        if(!prior.isEmpty()) {
            var row=prior.getFirst();
            if(number(row,"rule_id")!=ruleId)throw conflict("发放幂等键已用于其他优惠");
            return coupon(row);
        }
        Map<String,Object> template=promotion(ruleId);
        long shop=number(template,"shop_id");
        lockEpoch(shop);
        template=promotion(ruleId);
        Instant now=clock.now().toInstant(ZoneOffset.UTC);
        if(!"PLATFORM".equals(template.get("scope"))||!enabled(template,now))throw conflict("平台优惠已停用或不在有效期");
        // Used claims reserve their slot: reversing their bill can make them usable again.
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM tb_checkout_coupon WHERE owner_user_id=? AND shop_id=? AND revoked=0 AND end_time>?",Integer.class,user,shop,utc(now));
        if(count>=20)throw conflict("未过期且未撤销的平台券最多20张（已使用券保留账单撤销恢复名额）");
        long id=ids.nextId("checkout-coupon");
        jdbc.update("INSERT INTO tb_checkout_coupon(id,rule_id,owner_user_id,shop_id,request_key,rule_json,begin_time,end_time) VALUES (?,?,?,?,?,?,?,?)",
                id,ruleId,user,shop,key,encode(dto(template)),template.get("begin_time"),template.get("end_time"));
        return coupon(jdbc.queryForMap("SELECT * FROM tb_checkout_coupon WHERE id=?",id));
    }

    public Map<String,Object> revoke(long id) {
        permission.requireAdmin();
        var rows=jdbc.queryForList("SELECT * FROM tb_checkout_coupon WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty())throw missing("平台券不存在");
        var row=rows.getFirst();
        if(row.get("used_bill_id")!=null)throw conflict("已使用的平台券不能撤销");
        jdbc.update("UPDATE tb_checkout_coupon SET revoked=1 WHERE id=?",id);
        row.put("revoked",1);
        return coupon(row);
    }

    public List<Map<String,Object>> merchants(long shopId,Instant now) {
        var rows=jdbc.queryForList("SELECT * FROM tb_checkout_promotion WHERE shop_id=? AND scope='MERCHANT' AND active=1 AND begin_time<=? AND end_time>? ORDER BY id",shopId,utc(now),utc(now));
        if(rows.size()>20)throw conflict("有效门店优惠超过20项");
        return rows.stream().map(this::dto).toList();
    }

    public List<Map<String,Object>> coupons(long shopId,long user,Instant now) {
        var rows=jdbc.queryForList("SELECT * FROM tb_checkout_coupon WHERE shop_id=? AND owner_user_id=? AND revoked=0 AND used_bill_id IS NULL AND begin_time<=? AND end_time>? ORDER BY id",shopId,user,utc(now),utc(now));
        if(rows.size()>20)throw conflict("有效平台券超过20张");
        return rows.stream().map(this::coupon).toList();
    }

    public long lockEpoch(long shopId) {
        var rows=jdbc.queryForList("SELECT checkout_rule_version FROM tb_shop WHERE id=? FOR UPDATE",shopId);
        if(rows.isEmpty())throw missing("店铺不存在");
        return number(rows.getFirst(),"checkout_rule_version");
    }

    public static CheckoutCalculator.Rule rule(Map<String,Object> dto) {
        return new CheckoutCalculator.Rule(Long.parseLong(dto.get("id").toString()),dto.get("kind").toString(),
                number(dto,"threshold"),number(dto,"reduction"),Math.toIntExact(number(dto,"payableBps")),number(dto,"cap"),
                Math.toIntExact(number(dto,"priority")),Instant.parse(dto.get("endTime").toString()),
                flag(dto.get("stackMerchant")),flag(dto.get("stackVoucher")),flag(dto.get("stackPlatform")));
    }

    private Map<String,Object> promotion(long id) {
        var rows=jdbc.queryForList("SELECT * FROM tb_checkout_promotion WHERE id=?",id);
        if(rows.isEmpty())throw missing("优惠不存在");
        return rows.getFirst();
    }

    private Map<String,Object> dto(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();
        result.put("id",row.get("id").toString());result.put("shopId",row.get("shop_id").toString());
        for(String name:List.of("title","scope","kind","threshold","reduction","cap","priority","version"))result.put(name,row.get(name));
        result.put("payableBps",row.get("payable_bps"));
        result.put("stackMerchant",flag(row.get("stack_merchant")));result.put("stackVoucher",flag(row.get("stack_voucher")));
        result.put("stackPlatform",flag(row.get("stack_platform")));result.put("active",flag(row.get("active")));
        result.put("beginTime",instant(row,"begin_time").toString());result.put("endTime",instant(row,"end_time").toString());
        result.put("eligible",true);
        return result;
    }

    private Map<String,Object> coupon(Map<String,Object> row) {
        try {
            Map<String,Object> result=json.readValue(row.get("rule_json").toString(),new TypeReference<LinkedHashMap<String,Object>>(){});
            result.put("id",row.get("id").toString());result.put("ruleId",row.get("rule_id").toString());
            result.put("revoked",flag(row.get("revoked")));result.put("usedBillId",row.get("used_bill_id")==null?null:row.get("used_bill_id").toString());
            result.put("eligible",!flag(row.get("revoked"))&&row.get("used_bill_id")==null);
            return result;
        } catch(Exception e) {throw new IllegalStateException("平台券规则快照无效",e);}
    }

    private boolean enabled(Map<String,Object> row,Instant now) {
        return flag(row.get("active"))&&!now.isBefore(instant(row,"begin_time"))&&now.isBefore(instant(row,"end_time"));
    }
    private static Instant instant(Map<String,Object> row,String key) {return OrderLifecyclePersistence.time(row,key).toInstant(ZoneOffset.UTC);}
    private static LocalDateTime utc(Instant value) {return LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
    private static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    private static boolean flag(Object value) {return value instanceof Boolean b?b:value instanceof Number n&&n.intValue()!=0;}
    private boolean exists(String sql,Object... args) {return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args));}
    private static long amount(JsonNode input,String key) {
        long value=CheckoutRequests.number(input,key);
        if(value<0||value>100000000)throw ApiException.badRequest(key+"超出金额范围");
        return value;
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static ApiException conflict(String message) {return new ApiException(HttpStatus.CONFLICT,message);}
    private static ApiException missing(String message) {return new ApiException(HttpStatus.NOT_FOUND,message);}
}
