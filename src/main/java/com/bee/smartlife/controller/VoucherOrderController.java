package com.bee.smartlife.controller;

import com.bee.smartlife.dto.Result;
import com.bee.smartlife.service.IVoucherOrderService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端控制器
 *
 * @author beemines
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

  @Resource private IVoucherOrderService voucherOrderService;
  @Resource private com.bee.smartlife.service.impl.OrderLifecycleService lifecycle;

  @GetMapping("mine")
  public Result mine(
      @org.springframework.web.bind.annotation.RequestParam(required = false) Integer status,
      @org.springframework.web.bind.annotation.RequestParam(required = false) String beforeTime,
      @org.springframework.web.bind.annotation.RequestParam(required = false) Long beforeId,
      @org.springframework.web.bind.annotation.RequestParam(required = false) Integer limit) {
    return lifecycle.mine(status, beforeTime, beforeId, limit);
  }

  @GetMapping("detail/{id}")
  public Result detail(@PathVariable long id) {
    return lifecycle.detail(id);
  }

  @GetMapping("merchant/{id}")
  public Result merchant(@PathVariable long id) {
    return lifecycle.merchantDetail(id);
  }

  @GetMapping("capabilities")
  public Result capabilities() {
    return lifecycle.capabilities();
  }

  @PostMapping("{id}/pay")
  public Result pay(@PathVariable long id) {
    return lifecycle.pay(id);
  }

  @PostMapping("{id}/cancel")
  public Result cancel(@PathVariable long id) {
    return lifecycle.cancel(id);
  }

  @PostMapping("{id}/refund")
  public Result refund(@PathVariable long id) {
    return lifecycle.refund(id);
  }

  @PostMapping("{id}/redeem")
  public Result redeem(@PathVariable long id) {
    return lifecycle.redeem(id);
  }

  @PostMapping("seckill/{id}")
  public Result seckillVoucher(
      @PathVariable("id") Long voucherId,
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
