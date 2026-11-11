package com.bee.smartlife.controller;


import com.bee.smartlife.dto.Result;
import com.bee.smartlife.entity.Voucher;
import com.bee.smartlife.dto.VoucherWrite;
import com.bee.smartlife.security.PublicRead;
import com.bee.smartlife.security.ShopPermission;
import com.bee.smartlife.service.IVoucherService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author beemines
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;
    @Resource private ShopPermission permission;

    /**
     * 新增普通券
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    public Result addVoucher(@RequestBody VoucherWrite input) {
        permission.requireShopWrite(input.shopId());
        Voucher voucher=input.toVoucher(false);
        voucherService.save(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 新增秒杀券
     * @param voucher 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("seckill")
    public Result addSeckillVoucher(@RequestBody VoucherWrite input) {
        permission.requireShopWrite(input.shopId());
        Voucher voucher=input.toVoucher(true);
        voucherService.addSeckillVoucher(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    @PublicRead
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }
}
