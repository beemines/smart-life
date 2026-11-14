package com.bee.smartlife;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.bee.smartlife.service.impl.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

class VoucherBatchDiagnosticsTest {
 @ParameterizedTest @ValueSource(booleans={false,true})
 void processingFailuresLogIdentityAndTypeWithoutLeakingPayload(boolean failsBeforeRowRead) {
  var jdbc=mock(JdbcTemplate.class); var persistence=mock(VoucherOrderPersistence.class);
  var service=new VoucherBatchService(jdbc,null,null,null,null,persistence,null);
  when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("task_id",42L,"source_row",7,"order_id",99L)));
  when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
  var failure=new IllegalStateException("secret-token full-user-payload");
  if(failsBeforeRowRead) when(jdbc.queryForMap(anyString(),any(Object[].class))).thenThrow(failure);
  else {
   when(jdbc.queryForMap(anyString(),any(Object[].class))).thenReturn(Map.of("order_id",99L,"canonical_user_id",123L,"voucher_id",456L,"request_key","secret-token"));
   when(persistence.findRequest(99L)).thenThrow(failure).thenReturn(null);
  }
  var logger=(Logger)LoggerFactory.getLogger(VoucherBatchService.class);
  var capture=new ListAppender<ILoggingEvent>();capture.start();logger.addAppender(capture);
  try {
   service.recover();
   assertThat(capture.list).anySatisfy(event->{
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage()).contains("taskId=42","sourceRow=7","orderId=99","stage=","errorType=java.lang.IllegalStateException");
    assertThat(event.getFormattedMessage()).doesNotContain("secret-token","full-user-payload");
    assertThat(event.getThrowableProxy()).isNull();
   });
   // No compensating delete/reset: outer failure retains lease; inner failure can finish retryable.
   verify(jdbc,never()).update(startsWith("DELETE"),any(Object[].class));
  } finally {logger.detachAppender(capture);capture.stop();}
 }
}
