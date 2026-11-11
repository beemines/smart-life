package com.bee.smartlife.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bee.smartlife.dto.Result;
import com.bee.smartlife.entity.Voucher;
import com.bee.smartlife.mapper.VoucherMapper;
import com.bee.smartlife.security.ApiException;
import com.bee.smartlife.security.ShopPermission;
import com.bee.smartlife.service.IVoucherService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper,Voucher> implements IVoucherService {
    @Resource private VoucherPersistence persistence;
    @Resource private VoucherPublication publication;
    @Resource private ShopPermission permission;
    @Override public Result queryVoucherOfShop(Long shopId){return Result.ok(getBaseMapper().queryVoucherOfShop(shopId));}
    @Override
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void addVoucher(Voucher voucher) {
        permission.requireShopWrite(voucher.getShopId());
        if(voucher.getId()!=null)throw ApiException.badRequest("新增优惠券不能指定ID");
        persistence.create(voucher,false);
    }
    @Override
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void addSeckillVoucher(Voucher voucher) {
        permission.requireShopWrite(voucher.getShopId());
        if(voucher.getId()!=null)throw ApiException.badRequest("新增优惠券不能指定ID");
        persistence.create(voucher,true); // Returns only after SQL commits.
        publication.tryPublish(voucher.getId());
    }
    @Override
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Result changeStatus(Long id,int status) {
        if(id==null||id<=0||status<1||status>3)throw ApiException.badRequest("优惠券状态不正确");
        var lock=publication.lock(id);boolean held=false;
        try {
            held=lock.tryLock(3,TimeUnit.SECONDS);if(!held)return Result.fail("活动正在更新，请稍后重试");
            Voucher existing=persistence.load(id);
            if(existing==null)return Result.fail("优惠券不存在");
            permission.requireShopWrite(existing.getShopId()); // Never trust a caller-supplied shop ID.
            if(Integer.valueOf(status).equals(existing.getStatus()))return Result.ok();
            persistence.changeStatus(existing,status);
            if(Integer.valueOf(1).equals(existing.getType()))publication.tryPublish(id);
            return Result.ok();
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("活动更新被中断",interrupted);}
        finally{if(held)try{lock.unlock();}catch(RuntimeException unavailable){log.warn("活动更新锁等待恢复 voucherId={}",id);}}
    }
}
