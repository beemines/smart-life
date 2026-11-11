package com.bee.smartlife.dto;

import com.bee.smartlife.entity.Shop;
import com.bee.smartlife.security.ApiException;

/** Mutable shop fields only: counters, scores and timestamps are server-owned. */
public record ShopWrite(Long id,String name,Long typeId,String images,String area,String address,
                        Double x,Double y,Long avgPrice,String openHours) {
    public Shop toShop(boolean creating) {
        if(creating && id!=null) throw ApiException.badRequest("新增店铺不能指定ID");
        if(!creating && (id==null||id<=0)) throw ApiException.badRequest("店铺ID不正确");
        if(creating && (name==null||name.isBlank()||typeId==null||typeId<=0||address==null||address.isBlank()||x==null||y==null)) throw ApiException.badRequest("店铺资料不完整");
        var shop=new Shop();shop.setId(id);shop.setName(name);shop.setTypeId(typeId);shop.setImages(images);
        shop.setArea(area);shop.setAddress(address);shop.setX(x);shop.setY(y);shop.setAvgPrice(avgPrice);shop.setOpenHours(openHours);
        if(creating){shop.setImages(images==null?"":images);shop.setSold(0);shop.setComments(0);shop.setScore(0);}
        return shop;
    }
}
