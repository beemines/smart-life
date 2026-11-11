package com.bee.smartlife.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bee.smartlife.dto.Result;
import com.bee.smartlife.entity.Shop;
import com.bee.smartlife.mapper.ShopMapper;
import com.bee.smartlife.security.ShopPermission;
import com.bee.smartlife.service.IShopService;
import com.bee.smartlife.utils.SystemConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper,Shop> implements IShopService {
    @Resource private ShopCache cache;
    @Resource private ShopPersistence persistence;
    @Resource private ShopCacheSync synchronization;
    @Resource private ShopPermission permission;

    @Override
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Result queryById(Long id) {
        if(id==null||id<=0)return Result.fail("店铺不存在！");
        Shop shop=cache.find(id,()->persistence.load(id));
        return shop==null?Result.fail("店铺不存在！"):Result.ok(shop);
    }

    @Override
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Result create(Shop shop) {
        permission.requireAdmin();
        if(shop.getId()!=null)throw com.bee.smartlife.security.ApiException.badRequest("新增店铺不能指定ID");
        shop.setSold(0);shop.setComments(0);shop.setScore(0);
        Long id=persistence.create(shop); // SQL and pending sync version commit together.
        synchronization.trySync(id);     // Failure stays queryable through pending-aware SQL fallback.
        return Result.ok(id);
    }

    @Override
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Result update(Shop shop) {
        permission.requireShopWrite(shop.getId());
        var lock=cache.lock(shop.getId());boolean held=false;
        try {
            held=lock.tryLock(3,TimeUnit.SECONDS);
            if(!held)return Result.fail("店铺正在更新，请稍后重试");
            if(!persistence.update(shop))return Result.fail("店铺不存在！");
            // Hold the same lock through the real SQL commit and invalidation;
            // cache-miss readers cannot refill old data in between.
            synchronization.trySync(shop.getId());
            return Result.ok();
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new IllegalStateException("店铺更新被中断",interrupted);
        } finally {if(held)try{lock.unlock();}catch(RuntimeException unavailable){log.warn("店铺更新锁等待恢复 shopId={}",shop.getId());}}
    }

    @Override
    public Result queryShopByType(Integer typeId,Integer current,Double x,Double y) {
        Page<Shop> page=query().eq("type_id",typeId).page(new Page<>(current,SystemConstants.DEFAULT_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }
}
