package com.bee.smartlife.service.impl;

import com.bee.smartlife.mq.OrderCloseMQ;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
public class OrderLifecycleRecovery {
  private final JdbcTemplate jdbc;
  private final OrderLifecyclePersistence persistence;
  private final OrderStockReturn returns;
  private final OrderCloseMQ mq;

  @Value("${smartlife.orders.lifecycle-enabled:true}")
  private boolean enabled;

  @Scheduled(fixedDelayString = "${smartlife.orders.lifecycle-delay-ms:10000}")
  public void scheduled() {
    if (enabled) runBatch();
  }

  public void runBatch() {
    // MySQL sorts NULL first: unattempted work precedes retries, then the oldest
    // attempt wins. A fixed business-ID priority can starve later orders once
    // more poisoned rows exist than fit inside a retry cooldown.

    for (long id :
        jdbc.queryForList(
            "SELECT o.id FROM tb_voucher_order o JOIN tb_order_lifecycle l ON l.order_id=o.id WHERE"
                + " o.status=1 AND l.snapshot_valid=1 AND l.pay_deadline<=UTC_TIMESTAMP(6) AND"
                + " (l.close_attempt_at IS NULL OR"
                + " l.close_attempt_at<DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 30 SECOND)) ORDER BY"
                + " l.close_attempt_at,l.pay_deadline,o.id LIMIT 100",
            Long.class)) {
      jdbc.update(
          "UPDATE tb_order_lifecycle SET close_attempt_at=UTC_TIMESTAMP(6) WHERE order_id=?", id);
      try {
        persistence.action(id, "timeout", true);
      } catch (RuntimeException e) {
        jdbc.update(
            "UPDATE tb_order_lifecycle SET close_error=? WHERE order_id=?",
            e.getClass().getSimpleName(),
            id);
      }
    }
    for (long id :
        jdbc.queryForList(
            "SELECT o.id FROM tb_voucher_order o JOIN tb_order_lifecycle l ON l.order_id=o.id WHERE"
                + " o.status=5 AND (l.refund_attempt_at IS NULL OR"
                + " l.refund_attempt_at<DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 30 SECOND)) ORDER BY"
                + " l.refund_attempt_at,o.id LIMIT 100",
            Long.class)) {
      jdbc.update(
          "UPDATE tb_order_lifecycle SET refund_attempt_at=UTC_TIMESTAMP(6) WHERE order_id=?", id);
      try {
        persistence.action(id, "finishRefund", true);
      } catch (RuntimeException e) {
        log.warn(
            "Refund completion deferred orderId={} reason={}", id, e.getClass().getSimpleName());
      }
    }
    for (long id :
        jdbc.queryForList(
            "SELECT order_id FROM tb_order_stock_return WHERE state='PENDING' AND (last_attempt_at"
                + " IS NULL OR last_attempt_at<DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 30 SECOND)) ORDER"
                + " BY last_attempt_at,order_id LIMIT 100",
            Long.class)) syncReturn(id);
    for (long id :
        jdbc.queryForList(
            "SELECT l.order_id FROM tb_order_lifecycle l JOIN tb_voucher_order o ON o.id=l.order_id"
                + " WHERE l.snapshot_valid=1 AND l.close_scheduled=0 AND o.status=1 AND"
                + " (l.schedule_attempt_at IS NULL OR"
                + " l.schedule_attempt_at<DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 30 SECOND)) ORDER BY"
                + " l.schedule_attempt_at,l.order_id LIMIT 100",
            Long.class)) schedule(id);
  }

  public void schedule(long id) {
    int claimed =
        jdbc.update(
            "UPDATE tb_order_lifecycle l JOIN tb_voucher_order o ON o.id=l.order_id SET"
                + " l.schedule_attempt_at=UTC_TIMESTAMP(6) WHERE l.order_id=? AND"
                + " l.snapshot_valid=1 AND l.close_scheduled=0 AND o.status=1 AND"
                + " (l.schedule_attempt_at IS NULL OR"
                + " l.schedule_attempt_at<DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 30 SECOND))",
            id);
    if (claimed == 0) return;
    try {
      mq.send(id);
      jdbc.update("UPDATE tb_order_lifecycle SET close_scheduled=1 WHERE order_id=?", id);
    } catch (Exception e) {
      log.warn("Close scheduling deferred orderId={} reason={}", id, e.getClass().getSimpleName());
    }
  }

  public void syncReturn(long id) {
    int claimed =
        jdbc.update(
            "UPDATE tb_order_stock_return SET attempts=attempts+1,last_attempt_at=UTC_TIMESTAMP(6)"
                + " WHERE order_id=? AND state='PENDING' AND (last_attempt_at IS NULL OR"
                + " last_attempt_at<DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 30 SECOND))",
            id);
    if (claimed == 0) return;
    try {
      var row = jdbc.queryForMap("SELECT * FROM tb_order_stock_return WHERE order_id=?", id);
      long result =
          returns.apply(
              id,
              ((Number) row.get("voucher_id")).longValue(),
              ((Number) row.get("user_id")).longValue());
      jdbc.update(
          "UPDATE tb_order_stock_return SET state=?,last_error=NULL WHERE order_id=? AND"
              + " state='PENDING'",
          result <= 1 ? "DONE" : "REVIEW",
          id);
    } catch (RuntimeException e) {
      log.warn("Stock return deferred orderId={} reason={}", id, e.getClass().getSimpleName());
      jdbc.update(
          "UPDATE tb_order_stock_return SET last_error=? WHERE order_id=? AND state='PENDING'",
          e.getClass().getSimpleName(),
          id);
    }
  }
}
