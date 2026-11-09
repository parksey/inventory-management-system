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

// 앱을 거치지 않고 SQL로 직접 넣어, 같은 원장의 같은 메시지와 정해지지 않은 상태를 DB 제약이 막는지 확인한다
@Testcontainers
class OutboxMessageSchemaTest {

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
    }

    @Test
    @DisplayName("TC-48-05 같은 원장의 STOCK_CHANGED 메시지는 한 행만 들어간다")
    void 같은_원장_같은_이벤트() throws SQLException {
        insert("1", "PENDING");

        assertThatThrownBy(() -> insert("1", "PENDING"))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(UNIQUE_VIOLATION));
    }

    @Test
    @DisplayName("TC-48-06 status에 정해지지 않은 값은 CHECK 제약이 거절한다")
    void 정해지지_않은_상태() {
        assertThatThrownBy(() -> insert("1", "DONE"))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(CHECK_VIOLATION));
    }

    private static void insert(final String aggregateId, final String status) throws SQLException {
        execute("""
                INSERT INTO outbox_message (company_code, aggregate_type, aggregate_id, event_type, payload, status)
                VALUES ('COMPANY-A', 'STOCK_TRANSACTION', '%s', 'STOCK_CHANGED', '{}'::jsonb, '%s')
                """.formatted(aggregateId, status));
    }

    private static void execute(final String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
