package com.bee.smartlife.controller;


import com.bee.smartlife.dto.Result;
import com.bee.smartlife.service.IVoucherOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestKey) {
        return voucherOrderService.seckillVoucher(voucherId, requestKey);
    }

    @GetMapping("result/{orderId}")
    public Result result(@PathVariable Long orderId) {
        return voucherOrderService.queryOrderResult(orderId);
    }

    @GetMapping("request/{requestKey}")
    public Result requestResult(@PathVariable String requestKey) {
        return voucherOrderService.queryRequestResult(requestKey);
    }
}
