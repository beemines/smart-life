package com.bee.smartlife.dto;

import com.bee.smartlife.entity.Voucher;
import com.bee.smartlife.security.ApiException;
import java.time.LocalDateTime;

public record VoucherWrite(Long shopId,String title,String subTitle,String rules,Long payValue,Long actualValue,
                           Integer status,Integer stock,LocalDateTime beginTime,LocalDateTime endTime) {
    public Voucher toVoucher(boolean seckill) {
        if(shopId==null||shopId<=0||title==null||title.isBlank()||payValue==null||payValue<=0||actualValue==null||actualValue<payValue) throw ApiException.badRequest("优惠券资料不正确");
        int state=status==null?1:status;
        if(state<1||state>3) throw ApiException.badRequest("优惠券状态不正确");
        if(seckill && (stock==null||stock<0||beginTime==null||endTime==null||!beginTime.isBefore(endTime))) throw ApiException.badRequest("库存或活动时间不正确");
        var voucher=new Voucher();voucher.setShopId(shopId);voucher.setTitle(title);voucher.setSubTitle(subTitle);voucher.setRules(rules);
        voucher.setPayValue(payValue);voucher.setActualValue(actualValue);voucher.setType(seckill?1:0);voucher.setStatus(state);
        voucher.setStock(stock);voucher.setBeginTime(beginTime);voucher.setEndTime(endTime);return voucher;
    }
}
