package com.bee.smartlife.service.impl;

import com.bee.smartlife.security.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderLifecyclePersistence {
  private final JdbcTemplate jdbc;
  private final ShopPermission permission;

  @Value("${smartlife.orders.payment-window-seconds:900}")
  private int paymentWindow;

  @jakarta.annotation.PostConstruct
  public void validateConfiguration() {
    if (paymentWindow <= 0) throw new IllegalStateException("payment window must be positive");
  }

  public static final String SELECT =
      "SELECT"
          + " o.*,l.snapshot_valid,l.shop_id,l.shop_name,l.title,l.pay_value,l.actual_value,l.pay_deadline,l.use_begin_time,l.use_end_time,l.close_reason,l.cancel_accepted,l.refund_accepted,COALESCE(r.state,'NONE')"
          + " stock_return_state FROM tb_voucher_order o LEFT JOIN tb_order_lifecycle l ON"
          + " l.order_id=o.id LEFT JOIN tb_order_stock_return r ON r.order_id=o.id ";

  // Invoked inside the existing order insert transaction. Invalid capture is permanent.
  public void capture(long id) {
    if (paymentWindow <= 0) throw new IllegalStateException("payment window must be positive");
    jdbc.update(
        "INSERT IGNORE INTO"
            + " tb_order_lifecycle(order_id,snapshot_valid,shop_id,shop_name,title,pay_value,actual_value,pay_deadline,use_begin_time,use_end_time)"
            + " SELECT o.id,IF(v.id IS NOT NULL AND s.id IS NOT NULL AND v.pay_value>0 AND"
            + " v.actual_value>=v.pay_value AND v.title<>'' AND ((v.use_begin_time IS NULL AND"
            + " v.use_end_time IS NULL) OR (v.use_begin_time IS NOT NULL AND"
            + " v.use_end_time>v.use_begin_time)),1,0),v.shop_id,s.name,v.title,v.pay_value,v.actual_value,DATE_ADD(o.create_time,INTERVAL"
            + " ? SECOND),COALESCE(v.use_begin_time,o.create_time),COALESCE(v.use_end_time,DATE_ADD(o.create_time,INTERVAL"
            + " 30 DAY)) FROM tb_voucher_order o LEFT JOIN tb_voucher v ON v.id=o.voucher_id LEFT"
            + " JOIN tb_shop s ON s.id=v.shop_id WHERE o.id=?",
        paymentWindow,
        id);
  }

  public LocalDateTime now() {
    return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
  }

  public Map<String, Object> read(long id) {
    var rows = jdbc.queryForList(SELECT + " WHERE o.id=?", id);
    if (rows.isEmpty()) throw missing();
    return rows.getFirst();
  }

  public static ApiException missing() {
    return new ApiException(HttpStatus.NOT_FOUND, "订单不存在");
  }

  public boolean merchant(long user, Long shop) {
    return shop != null
        && Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_shop_permission WHERE user_id=? AND ((role='OWNER'"
                    + " AND shop_id=?) OR (role='ADMIN' AND shop_id=0)))",
                Boolean.class,
                user,
                shop));
  }

  /** Request-local grant read for the bounded current page; never cache across requests. */
  public Set<Long> manageableShops(long user, Set<Long> shops) {
    if (shops.isEmpty()) return Set.of();
    if (shops.size() > 50) throw new IllegalArgumentException("page shop limit exceeded");
    var args = new ArrayList<Object>();
    args.add(user);
    args.addAll(shops);
    String placeholders = String.join(",", Collections.nCopies(shops.size(), "?"));
    return Set.copyOf(jdbc.queryForList(
        "SELECT shop_id FROM tb_shop_permission WHERE user_id=? AND"
            + " ((role='ADMIN' AND shop_id=0) OR (role='OWNER' AND shop_id IN ("
            + placeholders + ")))" , Long.class, args.toArray()));
  }

  public static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  public static LocalDateTime time(Map<String, Object> row, String key) {
    Object v = row.get(key);
    return v == null
        ? null
        : v instanceof java.sql.Timestamp t
            ? LocalDateTime.ofInstant(t.toInstant(), ZoneOffset.UTC)
            : (LocalDateTime) v;
  }

  public static boolean valid(Map<String, Object> r) {
    return r.get("snapshot_valid") != null
        && number(r, "snapshot_valid") == 1
        && r.get("pay_deadline") != null
        && r.get("use_begin_time") != null
        && r.get("use_end_time") != null;
  }

  public static boolean before(Map<String, Object> r, String key, LocalDateTime now) {
    return time(r, key) != null && now.isBefore(time(r, key));
  }

  @Transactional
  public void action(long id, String action, boolean system) {
    // Lock first, then fetch DB clock: contention must never reuse a stale cutoff.
    var locks =
        jdbc.queryForList("SELECT id FROM tb_voucher_order WHERE id=? FOR UPDATE", Long.class, id);
    if (locks.isEmpty()) {
      if (system) return;
      throw missing();
    }
    var row = read(id);
    long user = system ? 0 : permission.currentUser();
    if (!system
        && (action.equals("redeem")
            ? !merchant(user, row.get("shop_id") == null ? null : number(row, "shop_id"))
            : user != number(row, "user_id"))) throw missing();
    LocalDateTime now = now();
    int status = (int) number(row, "status");
    if (!valid(row)) {
      if (system) return;
      throw ApiException.badRequest("订单快照不完整，需要核对");
    }
    if (action.equals("pay") && row.get("pay_time") != null) return;
    if (action.equals("cancel") && number(row, "cancel_accepted") == 1) return;
    if (action.equals("refund") && number(row, "refund_accepted") == 1) return;
    if (action.equals("redeem") && row.get("use_time") != null) return;
    switch (action) {
      case "pay" -> {
        require(status == 1 && before(row, "pay_deadline", now)
            && before(row, "use_end_time", now), "订单不可支付、已超时或券已过期");
        jdbc.update("UPDATE tb_voucher_order SET status=2,pay_time=? WHERE id=?", now, id);
      }
      case "cancel", "timeout" -> {
        if (system && (status != 1 || before(row, "pay_deadline", now))) return;
        require(status == 1, "订单不可取消");
        jdbc.update("UPDATE tb_voucher_order SET status=4 WHERE id=?", id);
        jdbc.update(
            "UPDATE tb_order_lifecycle SET close_reason=?,cancel_accepted=? WHERE order_id=?",
            action.equals("cancel") ? "USER_CANCEL" : "TIMEOUT",
            action.equals("cancel") ? 1 : 0,
            id);
        returnStock(row);
      }
      case "refund" -> {
        require(status == 2 && before(row, "use_end_time", now), "仅未使用且未过期的已支付订单可退款");
        jdbc.update("UPDATE tb_voucher_order SET status=5 WHERE id=?", id);
        jdbc.update("UPDATE tb_order_lifecycle SET refund_accepted=1 WHERE order_id=?", id);
      }
      case "finishRefund" -> {
        if (status != 5) return;
        jdbc.update("UPDATE tb_voucher_order SET status=6,refund_time=? WHERE id=?", now, id);
        returnStock(row);
      }
      case "redeem" -> {
        require(
            status == 2
                && !now.isBefore(time(row, "use_begin_time"))
                && before(row, "use_end_time", now),
            "订单不可核销或不在使用期");
        jdbc.update("UPDATE tb_voucher_order SET status=3,use_time=? WHERE id=?", now, id);
      }
      default -> throw new IllegalArgumentException(action);
    }
  }

  private void returnStock(Map<String, Object> row) {
    int added =
        jdbc.update(
            "INSERT IGNORE INTO tb_order_stock_return(order_id,voucher_id,user_id) VALUES (?,?,?)",
            row.get("id"),
            row.get("voucher_id"),
            row.get("user_id"));
    if (added == 1
        && jdbc.update(
                "UPDATE tb_seckill_voucher SET stock=stock+1 WHERE voucher_id=?",
                row.get("voucher_id"))
            != 1) throw new IllegalStateException("库存记录缺失，不能退回");
  }

  private static void require(boolean b, String message) {
    if (!b) throw ApiException.badRequest(message);
  }
}
