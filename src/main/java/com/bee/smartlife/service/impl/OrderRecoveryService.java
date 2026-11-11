package com.bee.smartlife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bee.smartlife.entity.*;
import com.bee.smartlife.mapper.*;
import com.bee.smartlife.service.IVoucherOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;

/** Internal operations only. Preview never changes business state or inventory. */
@Service
@RequiredArgsConstructor
public class OrderRecoveryService {
    private final VoucherOrderRequestMapper requests;
    private final VoucherOrderFailureMapper failures;
    private final VoucherOrderMapper orders;
    private final IVoucherOrderService service;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final VoucherOrderPersistence persistence;

    public record RecoveryItem(String orderId, Long voucherId, String state, String action,
                               Integer databaseStock, String redisStock, long ownershipRecords,
                               long pendingRequests, long createdOrders, long compensatedRequests,
                               String detail) {}
    public record RecoveryExecution(String orderId, String action, String outcome, String state) {}

    public List<RecoveryItem> preview(Long orderId, int limit, long minAgeSeconds) {
        validate(limit, minAgeSeconds);
        if (orderId != null && orderId <= 0) throw new IllegalArgumentException("订单ID必须为正整数");
        return snapshot(() -> {
            var cutoff = cutoff(minAgeSeconds);
            return candidates(orderId, limit).stream().map(id -> inspect(id, cutoff)).toList();
        });
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<RecoveryExecution> execute(Long orderId, int limit, long minAgeSeconds) {
        return executePlan(preview(orderId, limit, minAgeSeconds), minAgeSeconds);
    }

    /** Execute only the IDs in the printed preview; never silently expand a batch. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<RecoveryExecution> executePlan(List<RecoveryItem> plan, long minAgeSeconds) {
        validate(Math.max(1, plan.size()), minAgeSeconds);
        if (minAgeSeconds < 30) throw new IllegalArgumentException("恢复执行至少保留30秒在途窗口");
        var results = new ArrayList<RecoveryExecution>();
        for (RecoveryItem planned : plan) {
            long id = Long.parseLong(planned.orderId());
            RecoveryItem item = snapshot(() -> inspect(id, cutoff(minAgeSeconds)));
            if (Set.of("WAIT", "NONE").contains(item.action())) continue;
            long auditId = startAudit(item); // Must commit before any recovery action.
            String action;
            String outcome;
            try {
                action = "MANUAL".equals(item.action()) ? "MANUAL"
                        : service.recoverOrder(id, cutoff(minAgeSeconds)); // Rechecks under order/user locks.
                String after = state(id);
                outcome = "WAIT".equals(action) || "NONE".equals(action) ? "SKIPPED"
                        : "MANUAL".equals(action) || "REVIEW".equals(after) ? "MANUAL"
                        : "SEND_UNCONFIRMED".equals(action) ? "RETRY" : "SUCCEEDED";
            } catch (OrderRetryException retry) {
                action = "RETRY"; outcome = "RETRY";
            } catch (Exception failure) {
                action = failure.getClass().getSimpleName(); outcome = "FAILED";
            }
            String after = state(id);
            if (jdbc.update("UPDATE tb_voucher_order_recovery SET after_state=?,outcome=?,detail=? WHERE id=?",
                    after, outcome, action, auditId) != 1) throw new IllegalStateException("恢复审计写入失败");
            results.add(new RecoveryExecution(Long.toString(id), action, outcome, after));
        }
        return results;
    }

    private Set<Long> candidates(Long id, int limit) {
        if (id != null) return new LinkedHashSet<>(List.of(id));
        var timestamps = new HashMap<Long, LocalDateTime>();
        // Recent audit entries provide a bounded cooldown, including persistent
        // errors and manual cases, so one bad oldest record cannot starve the queue.
        var query = new QueryWrapper<VoucherOrderRequest>().in("state", "PREPARING", "PENDING", "REVIEW")
                .notExists("SELECT 1 FROM tb_voucher_order_recovery a WHERE a.order_id=tb_voucher_order_request.order_id AND a.update_time > DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 300 SECOND)")
                .orderByAsc("update_time").last("LIMIT " + limit);
        requests.selectList(query).forEach(row -> timestamps.put(row.getOrderId(), row.getUpdateTime()));
        var pending = new QueryWrapper<VoucherOrderFailure>().in("compensation_status", "pending", "manual")
                .notExists("SELECT 1 FROM tb_voucher_order_recovery a WHERE a.order_id=tb_voucher_order_failure.order_id AND a.update_time > DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 300 SECOND)")
                .orderByAsc("update_time").last("LIMIT " + limit);
        failures.selectList(pending).forEach(row -> timestamps.merge(row.getOrderId(), row.getUpdateTime(),
                (left, right) -> left.isAfter(right) ? left : right));
        return new LinkedHashSet<>(timestamps.entrySet().stream().sorted(Map.Entry.comparingByValue())
                .limit(limit).map(Map.Entry::getKey).toList());
    }

    private RecoveryItem inspect(long id, LocalDateTime cutoff) {
        VoucherOrderRequest request = requests.selectById(id);
        VoucherOrderFailure failure = failures.selectById(id);
        VoucherOrder existing = orders.selectById(id);
        Long voucherId = request != null ? request.getVoucherId() : failure != null ? failure.getVoucherId()
                : existing != null ? existing.getVoucherId() : null;
        if (voucherId == null) return new RecoveryItem(Long.toString(id), null, "NOT_FOUND", "NONE", null, null, 0, 0, 0, 0, "无对应记录");
        String state = request != null ? request.getState() : existing != null ? "CREATED"
                : "manual".equals(failure.getCompensationStatus()) ? "REVIEW" : "REJECTED";
        LocalDateTime updated = request != null ? request.getUpdateTime() : failure != null ? failure.getUpdateTime() : existing.getUpdateTime();
        String action;
        if (existing != null && failure != null) action = "MANUAL";
        else if (existing != null && request != null && "CREATED".equals(request.getState())) action = "NONE";
        else if (failure != null && "manual".equals(failure.getCompensationStatus())) action = "MANUAL";
        else if (updated != null && updated.isAfter(cutoff)) action = "WAIT";
        else if (existing != null) action = request == null ? "NONE" : "SYNC_CREATED";
        else if (failure != null) action = "pending".equals(failure.getCompensationStatus()) ? "COMPENSATE" : "NONE";
        else if ("PREPARING".equals(state)) action = "PREPARE";
        else if ("PENDING".equals(state)) action = "RESEND";
        else action = "MANUAL";
        String stockKey = "seckill:stock:" + voucherId;
        DataType stockType = redis.type(stockKey);
        String redisStock = stockType == DataType.STRING ? redis.opsForValue().get(stockKey) : stockType == DataType.NONE ? null : "INVALID_TYPE";
        String ownerKey = "seckill:reservation:" + voucherId;
        long owners = redis.type(ownerKey) == DataType.HASH ? redis.opsForHash().size(ownerKey) : 0;
        Integer dbStock = jdbc.query("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?",
                (row, index) -> row.getInt(1), voucherId).stream().findFirst().orElse(null);
        long pending = requests.selectCount(new QueryWrapper<VoucherOrderRequest>().eq("voucher_id", voucherId).eq("state", "PENDING"));
        long created = orders.selectCount(new QueryWrapper<VoucherOrder>().eq("voucher_id", voucherId));
        long compensated = failures.selectCount(new QueryWrapper<VoucherOrderFailure>().eq("voucher_id", voucherId).eq("compensation_status", "done"));
        return new RecoveryItem(Long.toString(id), voucherId, state, action, dbStock, redisStock, owners,
                pending, created, compensated, "WAIT".equals(action) ? "正常在途窗口，不补库存" : "只按原订单终态和归属恢复，不补库存差额");
    }

    private long startAudit(RecoveryItem item) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO tb_voucher_order_recovery(order_id,voucher_id,action,before_state) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, Long.parseLong(item.orderId()));
            statement.setLong(2, item.voucherId());
            statement.setString(3, item.action());
            statement.setString(4, item.state());
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private String state(long id) {
        VoucherOrderRequest request = persistence.findRequest(id);
        if (request != null) return request.getState();
        VoucherOrderFailure failure = persistence.findFailure(id);
        if (failure != null) return "manual".equals(failure.getCompensationStatus()) ? "REVIEW" : "REJECTED";
        return persistence.findOrder(id) == null ? "NOT_FOUND" : "CREATED";
    }

    private LocalDateTime cutoff(long seconds) {
        return jdbc.queryForObject("SELECT TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP)", LocalDateTime.class, -seconds);
    }

    private <T> T snapshot(Supplier<T> read) {
        var transaction = new TransactionTemplate(transactions);
        transaction.setReadOnly(true);
        return transaction.execute(status -> read.get());
    }

    private void validate(int limit, long age) {
        if (limit < 1 || limit > 500 || age < 0 || age > 2_592_000) throw new IllegalArgumentException("恢复批量或时间范围不正确");
    }
}
