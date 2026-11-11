package com.bee.smartlife.service.impl;

import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.bee.smartlife.entity.SeckillVoucher;
import com.bee.smartlife.entity.VoucherOrder;
import com.bee.smartlife.mapper.VoucherOrderFailureMapper;
import com.bee.smartlife.mapper.VoucherOrderMapper;
import com.bee.smartlife.mapper.VoucherOrderRequestMapper;
import com.bee.smartlife.service.ISeckillVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoucherOrderPersistenceTest {
    private VoucherOrderPersistence persistence;
    private VoucherOrderMapper mapper;
    private final VoucherOrder order = new VoucherOrder().setId(1L).setUserId(2L).setVoucherId(3L);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(VoucherOrderMapper.class);
        var vouchers = mock(ISeckillVoucherService.class);
        UpdateChainWrapper<SeckillVoucher> update = mock(UpdateChainWrapper.class, RETURNS_SELF);
        doReturn(update).when(update).setSql(anyString());
        doReturn(update).when(update).eq(anyString(), any());
        doReturn(update).when(update).gt(anyString(), any());
        when(update.update()).thenReturn(true);
        when(vouchers.update()).thenReturn(update);
        persistence = new VoucherOrderPersistence(mapper, mock(VoucherOrderFailureMapper.class), vouchers,
                mock(VoucherOrderRequestMapper.class));
    }

    @Test
    void unsuccessfulInsertMustEscapeTheTransactionAsFailure() {
        when(mapper.insert(order)).thenReturn(0);
        assertThatIllegalStateException().isThrownBy(() -> persistence.create(order));
    }

    @Test
    void duplicateKeyFailureIsNotSwallowedInsideTheTransaction() {
        var duplicate = new DuplicateKeyException("fixture unique-key race");
        when(mapper.insert(order)).thenThrow(duplicate);
        assertThatThrownBy(() -> persistence.create(order)).isSameAs(duplicate);
    }
}
