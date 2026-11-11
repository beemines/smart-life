package com.bee.smartlife.controller;


import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.bee.smartlife.dto.Result;
import com.bee.smartlife.entity.ShopType;
import com.bee.smartlife.security.PublicRead;
import com.bee.smartlife.service.IShopTypeService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.bee.smartlife.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author beemines
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final Cache<String,String> LOCAL_CACHE =
            Caffeine.newBuilder().initialCapacity(1024)
                    .maximumSize(10000L)
                    //店铺分类信息缓存十分钟过期
                    .expireAfterWrite(30L, TimeUnit.MINUTES)
                    .build();


    @GetMapping("list")
    @PublicRead
    public Result queryTypeList() {
        //构建缓存key
        String cacheKey = CACHE_SHOP_TYPE_KEY;
        //1.查询本地缓存
        String cacheValue = LOCAL_CACHE.getIfPresent(cacheKey);
        if(StrUtil.isNotBlank(cacheValue)){
            return Result.ok(JSONUtil.toList(cacheValue,ShopType.class));
        }

        //2..从redis查询店铺类型缓存
        String shopTypeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        //3..存在店铺类型缓存，直接返回
        if(StrUtil.isNotBlank(shopTypeJson)){
            //如果命中redis，存入本地缓存后返回
            LOCAL_CACHE.put(cacheKey,shopTypeJson);
            return Result.ok(JSONUtil.toList(shopTypeJson,ShopType.class));
        }
        //4..不存在，查询数据库
        List <ShopType> shopTypeList = typeService.query().orderByAsc("sort").list();
        //5..数据库查询失败，返回错误
        if(shopTypeList==null||shopTypeList.size()==0){
            return Result.fail("店铺类型查询失败！");
        }
        //6..将店铺类型缓存到本地缓存和redis
        String json = JSONUtil.toJsonStr(shopTypeList);
        LOCAL_CACHE.put(cacheKey,json);
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY,json,
                30,TimeUnit.MINUTES);
        //7.返回
        return Result.ok(shopTypeList);
    }
}
