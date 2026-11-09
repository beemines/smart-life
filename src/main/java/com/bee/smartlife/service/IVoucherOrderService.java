package com.bee.smartlife.service;

import com.bee.smartlife.dto.Result;
import com.bee.smartlife.dto.OrderProcessingResult;
import com.bee.smartlife.entity.VoucherOrder;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author beemines
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    OrderProcessingResult createVoucherOrder(VoucherOrder voucherOrder);
}
