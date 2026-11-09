package com.logistics.inventory.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// 앱을 거치지 않고 SQL로 직접 넣어, 메시지 하나에 ERP 전달이 하나뿐이고 정해진 상태만 들어가는지 DB 제약으로 확인한다
@Testcontainers
class ErpSyncSchemaTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @BeforeEach
    void clean() throws SQLException {
        execute("TRUNCATE erp_sync, outbox_message RESTART IDENTITY");
        execute("""
                INSERT INTO outbox_message (company_code, aggregate_type, aggregate_id, event_type, payload)
                VALUES ('COMPANY-A', 'STOCK_TRANSACTION', '1', 'STOCK_CHANGED', '{}'::jsonb)
                """);
    }

    @Test
    @DisplayName("TC-48-11 같은 메시지로 erp_sync는 한 행만 들어간다")
    void 같은_메시지() throws SQLException {
        insert("stx-1", "PENDING");

        assertThatThrownBy(() -> insert("stx-1-again", "PENDING"))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(UNIQUE_VIOLATION));
    }

    @Test
    @DisplayName("TC-48-11 status에 정해지지 않은 값은 CHECK 제약이 거절한다")
    void 정해지지_않은_상태() {
        assertThatThrownBy(() -> insert("stx-1", "DONE"))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(CHECK_VIOLATION));
    }

    private static void insert(final String key, final String status) throws SQLException {
        execute("""
                INSERT INTO erp_sync (outbox_message_id, erp_idempotency_key, status, next_retry_at)
                VALUES (1, '%s', '%s', now())
                """.formatted(key, status));
    }

    private static void execute(final String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
