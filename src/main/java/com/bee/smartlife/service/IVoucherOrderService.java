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

    Result seckillVoucher(Long voucherId, String requestKey);

    Result queryOrderResult(Long orderId);

    Result queryRequestResult(String requestKey);

    String recoverOrder(Long orderId, java.time.LocalDateTime staleBefore);

    OrderProcessingResult createVoucherOrder(VoucherOrder voucherOrder);
}
