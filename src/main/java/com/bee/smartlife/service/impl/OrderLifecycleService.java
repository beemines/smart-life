package com.bee.smartlife.service.impl;

import static com.bee.smartlife.service.impl.OrderLifecyclePersistence.*;

import com.bee.smartlife.dto.*;
import com.bee.smartlife.security.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderLifecycleService {
  private final OrderLifecyclePersistence persistence;
  private final JdbcTemplate jdbc;
  private final ShopPermission permission;
  private final OrderLifecycleRecovery recovery;

  public Result capabilities() {
    long user = permission.currentUser();
    return Result.ok(
        Map.of(
            "merchant",
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM tb_shop_permission WHERE user_id=? AND"
                        + " ((role='OWNER' AND shop_id>0) OR (role='ADMIN' AND shop_id=0)))",
                    Boolean.class,
                    user))));
  }

  public Result mine(Integer status, String beforeTime, Long beforeId, Integer limit) {
    long user = permission.currentUser();
    int size = limit == null ? 20 : limit;
    if (size < 1
        || size > 50
        || status != null && (status < 1 || status > 6)
        || (beforeTime == null) != (beforeId == null)
        || beforeId != null && beforeId <= 0) throw ApiException.badRequest("分页参数不正确");
    var args = new ArrayList<Object>();
    args.add(user);
    String sql = SELECT + " WHERE o.user_id=?";
    if (status != null) {
      sql += " AND o.status=?";
      args.add(status);
    }
    if (beforeTime != null) {
      LocalDateTime t;
      try {
        t = LocalDateTime.ofInstant(Instant.parse(beforeTime), ZoneOffset.UTC);
      } catch (Exception e) {
        throw ApiException.badRequest("分页时间不正确");
      }
      sql += " AND (o.create_time<? OR (o.create_time=? AND o.id<?))";
      args.add(t);
      args.add(t);
      args.add(beforeId);
    }
    sql += " ORDER BY o.create_time DESC,o.id DESC LIMIT ?";
    args.add(size + 1);
    var rows = jdbc.queryForList(sql, args.toArray());
    boolean more = rows.size() > size;
    if (more) rows = rows.subList(0, size);
    LocalDateTime now = persistence.now();
    var items = rows.stream().map(row -> view(row, user, now)).toList();
    var data = new LinkedHashMap<String, Object>();
    data.put("items", items);
    data.put("hasMore", more);
    data.put("nextTime", more ? items.getLast().createTime() : null);
    data.put("nextId", more ? items.getLast().id() : null);
    return Result.ok(data);
  }

  public Result detail(long id) {
    return detail(id, false);
  }

  public Result merchantDetail(long id) {
    return detail(id, true);
  }

  private Result detail(long id, boolean merchant) {
    long user = permission.currentUser();
    var row = persistence.read(id);
    if (merchant
        ? !persistence.merchant(user, row.get("shop_id") == null ? null : number(row, "shop_id"))
        : user != number(row, "user_id")) throw missing();
    return Result.ok(view(row, user, persistence.now()));
  }

  public Result pay(long id) {
    return act(id, "pay");
  }

  public Result cancel(long id) {
    return act(id, "cancel");
  }

  public Result refund(long id) {
    return act(id, "refund");
  }

  public Result redeem(long id) {
    return act(id, "redeem");
  }

  private Result act(long id, String action) {
    persistence.action(id, action, false);
    if (action.equals("refund")) persistence.action(id, "finishRefund", true);
    recovery.syncReturn(id);
    return detail(id, action.equals("redeem"));
  }

  public void afterCreated(long id) {
    recovery.schedule(id);
  }

  public void recover() {
    recovery.runBatch();
  }

  private OrderView view(Map<String, Object> row, long user, LocalDateTime now) {
    int status = (int) number(row, "status");
    boolean valid = valid(row), owner = user == number(row, "user_id");
    return new OrderView(
        row.get("id").toString(),
        row.get("voucher_id").toString(),
        row.get("shop_id") == null ? null : row.get("shop_id").toString(),
        (String) row.get("shop_name"),
        (String) row.get("title"),
        row.get("pay_value") == null ? null : number(row, "pay_value"),
        row.get("actual_value") == null ? null : number(row, "actual_value"),
        status,
        iso(row, "create_time"),
        iso(row, "pay_time"),
        iso(row, "use_time"),
        iso(row, "refund_time"),
        iso(row, "pay_deadline"),
        iso(row, "use_begin_time"),
        iso(row, "use_end_time"),
        (String) row.get("close_reason"),
        (String) row.get("stock_return_state"),
        valid && owner && status == 1 && before(row, "pay_deadline", now)
            && before(row, "use_end_time", now),
        valid && owner && status == 1,
        valid && owner && status == 2 && before(row, "use_end_time", now),
        valid
            && status == 2
            && !now.isBefore(time(row, "use_begin_time"))
            && before(row, "use_end_time", now)
            && persistence.merchant(
                user, row.get("shop_id") == null ? null : number(row, "shop_id")));
  }

  private String iso(Map<String, Object> row, String key) {
    var t = time(row, key);
    return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
  }
}
