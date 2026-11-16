package com.bee.smartlife.checkout;

import static com.bee.smartlife.checkout.CheckoutRequests.*;
import static com.bee.smartlife.service.impl.OrderLifecyclePersistence.*;

import com.bee.smartlife.security.*;
import com.bee.smartlife.service.impl.OrderLifecyclePersistence;
import com.bee.smartlife.utils.RedisIdWorker;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Bill and entitlement mutations commit together; no external payment or inventory return here. */
@Service
@RequiredArgsConstructor
@Transactional(isolation=Isolation.READ_COMMITTED)
public class CheckoutService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final ShopPermission permission;
  private final OrderLifecyclePersistence lifecycle;
  private final RedisIdWorker ids;
  private final CheckoutRuleService rules;

  public Map<String,Object> create(JsonNode input){
    long user=permission.currentUser(),shop=number(input,"shopId"),total=number(input,"total"),excluded=number(input,"excluded");
    money(total,excluded);String key=key(input);
    var shops=jdbc.queryForList("SELECT name FROM tb_shop WHERE id=?",shop);
    if(shops.isEmpty())throw missing();
    long id=ids.nextId("checkout");
    jdbc.update("INSERT INTO tb_checkout_bill(id,owner_user_id,shop_id,shop_name,request_key,original_total,original_excluded,total,excluded,created_at) VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",id,user,shop,shops.getFirst().get("name"),key,total,excluded,total,excluded,lifecycle.now());
    var row=one("SELECT * FROM tb_checkout_bill WHERE owner_user_id=? AND request_key=? FOR UPDATE",user,key);
    if(n(row,"shop_id")!=shop||n(row,"original_total")!=total||n(row,"original_excluded")!=excluded)throw conflict("请求键已用于不同账单");
    return view(row,false);
  }

  public Map<String,Object> detail(long id,boolean merchant){var row=bill(id,merchant);expire(row);return view(row,merchant);}

  public Map<String,Object> mine(Integer limit,String beforeTime,Long beforeId){
    long user=permission.currentUser();int size=limit==null?10:limit;
    if(size<1||size>50||(beforeTime==null)!=(beforeId==null)||beforeId!=null&&beforeId<=0)throw ApiException.badRequest("分页参数不正确");
    var args=new ArrayList<Object>();args.add(user);String sql="SELECT * FROM tb_checkout_bill WHERE owner_user_id=?";
    if(beforeTime!=null){LocalDateTime t;try{t=LocalDateTime.ofInstant(Instant.parse(beforeTime),ZoneOffset.UTC);}catch(Exception e){throw ApiException.badRequest("分页时间不正确");}sql+=" AND (created_at<? OR (created_at=? AND id<?))";args.add(t);args.add(t);args.add(beforeId);}
    sql+=" ORDER BY created_at DESC,id DESC LIMIT ?";args.add(size+1);
    var rows=jdbc.queryForList(sql,args.toArray());boolean more=rows.size()>size;if(more)rows=rows.subList(0,size);
    // Listing displays expired bills as closed; detail/mutations persist lazy expiry under a row lock.
    var items=rows.stream().map(r->view(r,false)).toList();var out=new LinkedHashMap<String,Object>();
    out.put("items",items);out.put("hasMore",more);
    if(more){out.put("nextTime",iso(rows.getLast(),"created_at"));out.put("nextId",rows.getLast().get("id").toString());}return out;
  }

  public Map<String,Object> confirm(long id,JsonNode input){
    var row=bill(id,true);expire(row);require(open(row),"账单已关闭或已结算");
    long total=number(input,"total"),excluded=number(input,"excluded");money(total,excluded);
    LocalDateTime now=lifecycle.now();
    jdbc.update("UPDATE tb_checkout_bill SET state='CONFIRMED',total=?,excluded=?,amount_version=amount_version+1,confirmed_by=?,confirmed_at=?,pay_deadline=?,quote_json=NULL WHERE id=?",total,excluded,permission.currentUser(),now,now.plusSeconds(900),id);
    return view(read(id),true);
  }

  public Map<String,Object> cancel(long id){
    var row=bill(id,false);expire(row);
    if(!"CANCELLED".equals(row.get("state"))){require(open(row),"已结算账单不可取消");jdbc.update("UPDATE tb_checkout_bill SET state='CANCELLED',quote_json=NULL WHERE id=?",id);}
    return view(read(id),false);
  }

  public Map<String,Object> options(long id,Long beforeVoucherId){
    var row=bill(id,false);expire(row);require("CONFIRMED".equals(row.get("state")),"请先由商户确认账单");
    if(beforeVoucherId!=null&&beforeVoucherId<=0)throw ApiException.badRequest("券游标不正确");
    Instant now=clock();long user=n(row,"owner_user_id"),shop=n(row,"shop_id");
    var vouchers=beforeVoucherId==null
        ?jdbc.queryForList(SELECT+" WHERE o.user_id=? AND l.shop_id=? AND o.status=2 ORDER BY o.id DESC LIMIT 51",user,shop)
        :jdbc.queryForList(SELECT+" WHERE o.user_id=? AND l.shop_id=? AND o.status=2 AND o.id<? ORDER BY o.id DESC LIMIT 51",user,shop,beforeVoucherId);
    boolean more=vouchers.size()>50;if(more)vouchers=vouchers.subList(0,50);
    var candidates=new ArrayList<Map<String,Object>>();
    for(var v:vouchers){var out=new LinkedHashMap<String,Object>();out.put("id",v.get("id").toString());out.put("title",v.get("title"));out.put("price",v.get("pay_value"));out.put("face",v.get("actual_value"));out.put("payValue",v.get("pay_value"));out.put("actualValue",v.get("actual_value"));out.put("threshold",v.get("checkout_threshold"));out.put("stackMerchant",yes(v,"checkout_stack_merchant"));out.put("stackPlatform",yes(v,"checkout_stack_platform"));
      try{paid(v,row,now);out.put("eligible",true);}catch(ApiException e){out.put("eligible",false);out.put("disabledReason",e.getMessage());}candidates.add(out);}
    var result=new LinkedHashMap<String,Object>();result.put("vouchers",candidates);result.put("hasMoreVouchers",more);
    if(more)result.put("nextVoucherId",vouchers.getLast().get("id").toString());
    result.put("merchants",rules.merchants(shop,now));result.put("platforms",rules.coupons(shop,user,now));return result;
  }

  public Map<String,Object> quote(long id,JsonNode input){
    var row=bill(id,false);expire(row);require("CONFIRMED".equals(row.get("state")),"请先由商户确认账单");
    long epoch=rules.lockEpoch(n(row,"shop_id"));Instant now=clock();require(now.isBefore(at(row,"pay_deadline")),"账单支付已超时");
    var calculation=calculate(row,input,now);ObjectNode quote=mapper.createObjectNode();
    quote.put("token",UUID.randomUUID().toString());quote.put("expiresAt",now.plusSeconds(120).toString());quote.put("amountVersion",n(row,"amount_version"));quote.put("epoch",epoch);
    quote.set("calculation",calculation(calculation));
    ObjectNode selection=quote.putObject("selection");selection.put("automatic",false);putId(selection,"voucherId",calculation.voucherId());putId(selection,"merchantId",calculation.merchantId());putId(selection,"platformId",calculation.platformId());
    jdbc.update("UPDATE tb_checkout_bill SET quote_json=? WHERE id=?",json(quote),id);
    return view(read(id),false);
  }

  public Map<String,Object> pay(long id,JsonNode input){
    var row=bill(id,false);String token=text(input,"token",64);
    JsonNode settled=parse(row.get("settlement_json"));
    if("PAID".equals(row.get("state"))||"REVERSED".equals(row.get("state"))){require(settled!=null&&token.equals(settled.path("token").asText()),"报价不匹配");return view(row,false);}
    expire(row);require("CONFIRMED".equals(row.get("state")),"账单不可支付");
    JsonNode quote=parse(row.get("quote_json"));require(quote!=null&&token.equals(quote.path("token").asText()),"报价已失效，请重新试算");
    JsonNode selection=quote.path("selection");Long voucher=optionalId(selection,"voucherId"),platform=optionalId(selection,"platformId");
    if(voucher!=null)one("SELECT id FROM tb_voucher_order WHERE id=? FOR UPDATE",voucher);
    if(platform!=null)one("SELECT id FROM tb_checkout_coupon WHERE id=? FOR UPDATE",platform);
    long epoch=rules.lockEpoch(n(row,"shop_id"));Instant now=clock();
    require(now.isBefore(Instant.parse(quote.path("expiresAt").asText()))&&now.isBefore(at(row,"pay_deadline")),"报价或账单已超时，请重新确认");
    require(epoch==quote.path("epoch").asLong()&&n(row,"amount_version")==quote.path("amountVersion").asLong(),"规则或金额已更新，请重新试算");
    var computed=calculate(row,selection,now);
    require(sameCalculation(mapper,computed,quote.path("calculation")),"优惠结果已变更，请重新试算");
    LocalDateTime time=LocalDateTime.ofInstant(now,ZoneOffset.UTC);
    if(voucher!=null){
      require(jdbc.update("UPDATE tb_voucher_order SET status=3,use_time=? WHERE id=? AND status=2",time,voucher)==1,"代金券已使用");
      jdbc.update("INSERT INTO tb_checkout_voucher_use(order_id,bill_id,used_at) VALUES (?,?,?)",voucher,id,time);
    }
    if(platform!=null)require(jdbc.update("UPDATE tb_checkout_coupon SET used_bill_id=? WHERE id=? AND used_bill_id IS NULL AND revoked=0",id,platform)==1,"平台券已使用或已撤销");
    ObjectNode snapshot=(ObjectNode)quote.deepCopy();snapshot.put("paidAt",now.toString());
    jdbc.update("UPDATE tb_checkout_bill SET state='PAID',settlement_json=?,paid_at=? WHERE id=?",json(snapshot),time,id);
    return view(read(id),false);
  }

  public Map<String,Object> reverse(long id,JsonNode input){
    var row=bill(id,true);
    require(input!=null&&input.isObject()&&input.size()==1&&input.has("reason"),"仅支持全额撤销，不接受部分退款金额");
    String reason=text(input,"reason",255);
    if("REVERSED".equals(row.get("state")))return view(row,true);
    require("PAID".equals(row.get("state")),"仅已结算账单可撤销");
    JsonNode snapshot=parse(row.get("settlement_json")),selection=snapshot.path("selection");
    Long voucher=optionalId(selection,"voucherId"),platform=optionalId(selection,"platformId");
    if(voucher!=null){
      var order=one("SELECT id,status FROM tb_voucher_order WHERE id=? FOR UPDATE",voucher);
      var use=one("SELECT bill_id FROM tb_checkout_voucher_use WHERE order_id=?",voucher);
      require(n(order,"status")==3&&n(use,"bill_id")==id,"核销关联异常，需要核对");
    }
    if(platform!=null){var coupon=one("SELECT used_bill_id FROM tb_checkout_coupon WHERE id=? FOR UPDATE",platform);require(coupon.get("used_bill_id")!=null&&n(coupon,"used_bill_id")==id,"平台券关联异常，需要核对");}
    // All rows locked before obtaining the authoritative action time.
    LocalDateTime now=lifecycle.now();
    if(voucher!=null){jdbc.update("UPDATE tb_voucher_order SET status=2,use_time=NULL WHERE id=?",voucher);jdbc.update("DELETE FROM tb_checkout_voucher_use WHERE order_id=? AND bill_id=?",voucher,id);}
    if(platform!=null)jdbc.update("UPDATE tb_checkout_coupon SET used_bill_id=NULL WHERE id=?",platform);
    long refund=snapshot.path("calculation").path("payable").asLong();
    var result=new LinkedHashMap<String,Object>();result.put("refundAmount",refund);result.put("voucherId",voucher==null?null:voucher.toString());result.put("platformId",platform==null?null:platform.toString());result.put("note","仅退本次模拟补付款；券期限不延长，原购券款不在此退款");
    jdbc.update("INSERT INTO tb_checkout_reversal(bill_id,actor_user_id,reason,refund_amount,result_json,created_at) VALUES (?,?,?,?,?,?)",id,permission.currentUser(),reason,refund,json(result),now);
    jdbc.update("UPDATE tb_checkout_bill SET state='REVERSED',reversed_at=? WHERE id=?",now,id);
    return view(read(id),true);
  }

  private CheckoutCalculator.Calculation calculate(Map<String,Object> row,JsonNode input,Instant now){
    long shop=n(row,"shop_id"),user=n(row,"owner_user_id");Long voucher=optionalId(input,"voucherId"),merchant=optionalId(input,"merchantId"),platform=optionalId(input,"platformId");
    CheckoutCalculator.PaidVoucher paid=null;if(voucher!=null)paid=paid(one(SELECT+" WHERE o.id=? AND o.user_id=? AND l.shop_id=?",voucher,user,shop),row,now);
    var merchants=rules.merchants(shop,now);var platforms=rules.coupons(shop,user,now);
    try{
      if(flag(input,"automatic"))return CheckoutCalculator.best(n(row,"total"),n(row,"excluded"),merchants.stream().map(CheckoutRuleService::rule).toList(),paid,platforms.stream().map(CheckoutRuleService::rule).toList());
      return CheckoutCalculator.calculate(n(row,"total"),n(row,"excluded"),find(merchants,merchant),paid,find(platforms,platform));
    }catch(IllegalArgumentException e){throw ApiException.badRequest(e.getMessage());}
  }

  private CheckoutCalculator.Rule find(List<Map<String,Object>> offers,Long id){
    if(id==null)return null;
    return offers.stream().filter(r->r.get("id").toString().equals(id.toString())).findFirst().map(CheckoutRuleService::rule).orElseThrow(()->ApiException.badRequest("优惠已失效、被使用或不属于此账单"));
  }

  private CheckoutCalculator.PaidVoucher paid(Map<String,Object> order,Map<String,Object> row,Instant now){
    require(n(order,"user_id")==n(row,"owner_user_id")&&order.get("shop_id")!=null&&n(order,"shop_id")==n(row,"shop_id"),"代金券不属于本人或本店");
    require(valid(order)&&n(order,"status")==2,"代金券未支付或已使用");
    require(!now.isBefore(at(order,"use_begin_time"))&&now.isBefore(at(order,"use_end_time")),"代金券不在使用期");
    require(yes(order,"checkout_enabled")&&order.get("checkout_threshold")!=null,"此历史券仅支持原核销流程");
    return new CheckoutCalculator.PaidVoucher(n(order,"id"),n(order,"pay_value"),n(order,"actual_value"),n(order,"checkout_threshold"),yes(order,"checkout_stack_merchant"),yes(order,"checkout_stack_platform"));
  }

  private Map<String,Object> bill(long id,boolean merchant){
    var row=one("SELECT * FROM tb_checkout_bill WHERE id=? FOR UPDATE",id);
    if(merchant?!lifecycle.merchant(permission.currentUser(),n(row,"shop_id")):permission.currentUser()!=n(row,"owner_user_id"))throw missing();
    return row;
  }
  private Map<String,Object> read(long id){return one("SELECT * FROM tb_checkout_bill WHERE id=?",id);}
  private Map<String,Object> one(String sql,Object...args){var r=jdbc.queryForList(sql,args);if(r.isEmpty())throw missing();return r.getFirst();}
  private void expire(Map<String,Object> row){if(expired(row)){jdbc.update("UPDATE tb_checkout_bill SET state='CANCELLED',quote_json=NULL WHERE id=?",row.get("id"));row.put("state","CANCELLED");row.put("quote_json",null);}}
  private boolean expired(Map<String,Object> row){return "CONFIRMED".equals(row.get("state"))&&!clock().isBefore(at(row,"pay_deadline"));}
  private boolean open(Map<String,Object> row){return "DRAFT".equals(row.get("state"))||"CONFIRMED".equals(row.get("state"));}
  private Instant clock(){return lifecycle.now().toInstant(ZoneOffset.UTC);}
  private Map<String,Object> view(Map<String,Object> row,boolean merchant){
    boolean closed=expired(row),owner=permission.currentUser()==n(row,"owner_user_id"),confirmed="CONFIRMED".equals(row.get("state"))&&!closed;
    var out=new LinkedHashMap<String,Object>();out.put("id",row.get("id").toString());out.put("shopId",row.get("shop_id").toString());out.put("ownerUserId",row.get("owner_user_id").toString());out.put("shopName",row.get("shop_name"));out.put("state",closed?"CANCELLED":row.get("state"));out.put("total",n(row,"total"));out.put("excluded",n(row,"excluded"));out.put("amountVersion",n(row,"amount_version"));out.put("createdAt",iso(row,"created_at"));out.put("payDeadline",iso(row,"pay_deadline"));
    JsonNode q=closed?null:parse(row.get("quote_json"));out.put("quote",q);out.put("settlement",parse(row.get("settlement_json")));
    out.put("canConfirm",merchant&&!closed&&open(row));out.put("canQuote",owner&&confirmed);out.put("canPay",owner&&confirmed&&q!=null&&clock().isBefore(Instant.parse(q.path("expiresAt").asText())));out.put("canCancel",owner&&!closed&&open(row));out.put("canReverse",merchant&&"PAID".equals(row.get("state")));
    if("REVERSED".equals(row.get("state"))){var r=jdbc.queryForList("SELECT reason,refund_amount,created_at,result_json FROM tb_checkout_reversal WHERE bill_id=?",row.get("id"));if(!r.isEmpty()){out.put("reversal",parse(r.getFirst().get("result_json")));out.put("reversalReason",r.getFirst().get("reason"));}}
    return out;
  }
  private ObjectNode calculation(CheckoutCalculator.Calculation c){ObjectNode n=mapper.valueToTree(c);putId(n,"merchantId",c.merchantId());putId(n,"voucherId",c.voucherId());putId(n,"platformId",c.platformId());return n;}
  static boolean sameCalculation(ObjectMapper mapper,CheckoutCalculator.Calculation computed,JsonNode stored){
    // SQL JSON parsing may narrow 18000L to IntNode; compare typed monetary values, not node classes.
    return computed.equals(mapper.convertValue(stored,CheckoutCalculator.Calculation.class));
  }
  private static void putId(ObjectNode node,String key,Long id){if(id==null)node.putNull(key);else node.put(key,id.toString());}
  private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("账单快照序列化失败",e);}}
  private JsonNode parse(Object value){if(value==null)return null;try{return mapper.readTree(value.toString());}catch(Exception e){throw new IllegalStateException("账单快照损坏，需要核对",e);}}
  static long n(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
  static boolean yes(Map<String,Object> row,String key){Object v=row.get(key);return Boolean.TRUE.equals(v)||v instanceof Number num&&num.intValue()==1;}
  static Instant at(Map<String,Object> row,String key){return time(row,key).toInstant(ZoneOffset.UTC);}
  static String iso(Map<String,Object> row,String key){return row.get(key)==null?null:at(row,key).toString();}
  private static void require(boolean condition,String message){if(!condition)throw ApiException.badRequest(message);}
  private static ApiException conflict(String message){return new ApiException(HttpStatus.CONFLICT,message);}
}
