package com.bee.smartlife;

import com.bee.smartlife.entity.VoucherOrder;
import com.bee.smartlife.service.IVoucherOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "SMARTLIFE_INTEGRATION_TESTS", matches = "true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderSafetyIT {
    private static final String MIGRATION = "db/migrations/2026-11-08-order-user-voucher-unique.sql";
    private static final AtomicLong IDS = new AtomicLong(ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 2_000_000_000_000L));
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired IVoucherOrderService orders;
    private final List<Long> ownedVouchers = new ArrayList<>();
    private long voucherId;
    private long userId;

    @BeforeEach
    void createSyntheticVoucher() {
        voucherId = newVoucher();
        userId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    }

    @AfterEach
    void cleanOnlyOwnedFixtures() {
        for (long owned : ownedVouchers) {
            jdbc.update("DELETE FROM tb_voucher_order_recovery WHERE voucher_id=?", owned);
            jdbc.update("DELETE FROM tb_voucher_order_request WHERE voucher_id=?", owned);
            jdbc.update("DELETE FROM tb_voucher_order_failure WHERE voucher_id=?", owned);
            jdbc.update("DELETE FROM tb_voucher_order WHERE voucher_id=?", owned);
            jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id=?", owned);
            jdbc.update("DELETE FROM tb_voucher WHERE id=?", owned);
        }
    }

    @Test
    void concurrentWritersCannotCreateDuplicatePairOrCommitExtraStock() throws Exception {
        // Deliberately bypass application locks/checks to prove the database is a final guard.
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        int committed = 0;
        try (var pool = Executors.newFixedThreadPool(8)) {
            var attempts = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                attempts.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(15, TimeUnit.SECONDS)) throw new AssertionError("start barrier timed out");
                    try {
                        transaction.executeWithoutResult(status -> {
                            assertThat(jdbc.update("UPDATE tb_seckill_voucher SET stock=stock-1 WHERE voucher_id=? AND stock>0", voucherId)).isEqualTo(1);
                            insertOrder(IDS.incrementAndGet(), userId, voucherId);
                        });
                        return true;
                    } catch (DuplicateKeyException duplicate) {
                        return false; // Caught outside the transaction, after its deduction rolled back.
                    }
                }));
            }
            try {
                assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            for (Future<Boolean> attempt : attempts) if (attempt.get(25, TimeUnit.SECONDS)) committed++;
        }
        assertThat(committed).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?", Long.class, voucherId)).isEqualTo(1);
        assertThat(stock()).isEqualTo(9);
    }

    @Test
    void differentUsersOrDifferentVouchersAreAllowed() {
        long otherVoucher = newVoucher();
        insertOrder(IDS.incrementAndGet(), userId, voucherId);
        insertOrder(IDS.incrementAndGet(), userId + 1, voucherId);
        insertOrder(IDS.incrementAndGet(), userId, otherVoucher);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE user_id=? AND voucher_id IN (?,?)",
                Long.class, userId, voucherId, otherVoucher)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?", Long.class, voucherId)).isEqualTo(2);
    }

    @Test
    void primaryKeyPayloadCollisionIsRejectedBeforeStockDeduction() {
        long existingId = IDS.incrementAndGet();
        insertOrder(existingId, userId + 1, voucherId);
        var colliding = new VoucherOrder().setId(existingId).setUserId(userId).setVoucherId(voucherId);
        assertThatThrownBy(() -> orders.createVoucherOrder(colliding)).isInstanceOf(IllegalArgumentException.class);
        assertThat(stock()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT user_id FROM tb_voucher_order WHERE id=?", Long.class, existingId)).isEqualTo(userId + 1);
    }

    @Test
    void sequentialDuplicateDoesNotDeductStockAgain() {
        orders.createVoucherOrder(new VoucherOrder().setId(IDS.incrementAndGet()).setUserId(userId).setVoucherId(voucherId));
        orders.createVoucherOrder(new VoucherOrder().setId(IDS.incrementAndGet()).setUserId(userId).setVoucherId(voucherId));
        assertThat(stock()).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?", Long.class, voucherId)).isEqualTo(1);
    }

    @Test
    void realMigrationAddsTheGuardToALegacyTableWithoutDeletingRecords() throws Exception {
        withLegacyTable(connection -> {
            execute(connection, "INSERT INTO tb_voucher_order VALUES (1,7,9)");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION));
            assertThatThrownBy(() -> execute(connection, "INSERT INTO tb_voucher_order VALUES (2,7,9)"))
                    .isInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class);
            assertThat(countRows(connection)).isEqualTo(1);
        });
    }

    @Test
    void realMigrationRefusesExistingDuplicatesAndKeepsBothRowsUntouched() throws Exception {
        withLegacyTable(connection -> {
            execute(connection, "INSERT INTO tb_voucher_order VALUES (1,7,9),(2,7,9)");
            assertThatThrownBy(() -> ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION)))
                    .isInstanceOf(ScriptStatementFailedException.class)
                    .hasRootCauseInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class);
            assertThat(countRows(connection)).isEqualTo(2);
        });
    }

    private void withLegacyTable(SqlWork work) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            // A TEMPORARY table shadows the real name on this connection only.
            // DROP TEMPORARY cannot remove the application's actual orders table.
            execute(connection, "DROP TEMPORARY TABLE IF EXISTS tb_voucher_order");
            execute(connection, "CREATE TEMPORARY TABLE tb_voucher_order (id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,voucher_id BIGINT NOT NULL) ENGINE=InnoDB");
            try {
                work.run(connection);
            } finally {
                execute(connection, "DROP TEMPORARY TABLE tb_voucher_order");
            }
        }
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    private long countRows(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM tb_voucher_order")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private long newVoucher() {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO tb_voucher (shop_id,title,pay_value,actual_value,type,status) VALUES (1,?,100,200,1,1)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "order-safety-it-" + UUID.randomUUID());
            return statement;
        }, key);
        long id = Objects.requireNonNull(key.getKey()).longValue();
        ownedVouchers.add(id);
        jdbc.update("INSERT INTO tb_seckill_voucher (voucher_id,stock,begin_time,end_time) VALUES (?,10,DATE_SUB(NOW(), INTERVAL 1 HOUR),DATE_ADD(NOW(), INTERVAL 1 HOUR))", id);
        return id;
    }

    private void insertOrder(long id, long user, long voucher) {
        jdbc.update("INSERT INTO tb_voucher_order (id,user_id,voucher_id) VALUES (?,?,?)", id, user, voucher);
    }

    private int stock() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, voucherId));
    }

    @FunctionalInterface
    private interface SqlWork { void run(Connection connection) throws Exception; }
}
