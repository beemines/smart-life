package com.bee.smartlife.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.bee.smartlife.entity.SeckillVoucher;
import com.bee.smartlife.entity.VoucherOrder;
import com.bee.smartlife.service.ISeckillVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class VoucherOrderServiceImplTest {
    private VoucherOrderServiceImpl service;
    private RLock lock;
    private VoucherOrder order;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = spy(new VoucherOrderServiceImpl());
        RedissonClient redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        ReflectionTestUtils.setField(service, "redissonClient", redisson);

        QueryChainWrapper<VoucherOrder> query = mock(QueryChainWrapper.class, RETURNS_SELF);
        doReturn(query).when(query).eq(anyString(), any());
        when(query.count()).thenReturn(0L);
        doReturn(query).when(service).query();
        ISeckillVoucherService vouchers = mock(ISeckillVoucherService.class);
        UpdateChainWrapper<SeckillVoucher> update = mock(UpdateChainWrapper.class, RETURNS_SELF);
        doReturn(update).when(update).setSql(anyString());
        doReturn(update).when(update).eq(anyString(), any());
        doReturn(update).when(update).gt(anyString(), any());
        when(update.update()).thenReturn(true);
        when(vouchers.update()).thenReturn(update);
        ReflectionTestUtils.setField(service, "seckillVoucherService", vouchers);
        order = new VoucherOrder().setId(1L).setUserId(2L).setVoucherId(3L);
    }

    @Test
    void falseSaveResultMustEscapeTheTransactionalMethodAsFailure() {
        doReturn(false).when(service).save(any(VoucherOrder.class));
        assertThatIllegalStateException().isThrownBy(() -> service.createVoucherOrder(order));
        verify(lock).unlock();
    }

    @Test
    void duplicateKeyFailureIsNotSwallowedInsideTheTransaction() {
        var duplicate = new DuplicateKeyException("fixture primary-key collision");
        doThrow(duplicate).when(service).save(any(VoucherOrder.class));
        assertThatThrownBy(() -> service.createVoucherOrder(order)).isSameAs(duplicate);
        verify(lock).unlock();
    }
}
