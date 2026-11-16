package com.bee.smartlife.checkout;

import com.bee.smartlife.security.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** Reject fractional JSON numbers instead of Jackson's permissive long coercion. */
public final class CheckoutRequests {
  private CheckoutRequests() {}
  public static long number(JsonNode body,String key) {
    JsonNode n=body==null?null:body.get(key);
    if(n==null || !(n.isIntegralNumber()&&n.canConvertToLong() || n.isTextual()&&n.asText().matches("[0-9]{1,19}")))throw ApiException.badRequest(key+"必须为整数");
    try{return n.isTextual()?Long.parseLong(n.asText()):n.longValue();}catch(Exception e){throw ApiException.badRequest(key+"超出范围");}
  }
  public static Long optionalId(JsonNode body,String key){return body==null||!body.hasNonNull(key)?null:number(body,key);}
  public static String text(JsonNode body,String key,int max){
    JsonNode n=body==null?null:body.get(key);
    if(n==null||!n.isTextual()||n.asText().isBlank()||n.asText().length()>max)throw ApiException.badRequest(key+"不正确");
    return n.asText().trim();
  }
  public static String key(JsonNode body){String k=text(body,"requestKey",64);if(!k.matches("[a-zA-Z0-9_-]{8,64}"))throw ApiException.badRequest("requestKey不正确");return k;}
  public static boolean flag(JsonNode body,String key){
    if(!body.hasNonNull(key))return false;
    if(!body.get(key).isBoolean())throw ApiException.badRequest(key+"必须为布尔值");
    return body.get(key).booleanValue();
  }
  public static Instant instant(JsonNode body,String key){
    try{return Instant.parse(text(body,key,40));}catch(Exception e){throw ApiException.badRequest(key+"必须包含UTC时区");}
  }
  public static void money(long total,long excluded){
    if(total<1||total>100000000||excluded<0||excluded>total)throw ApiException.badRequest("消费金额或不参与优惠金额不正确");
  }
}
