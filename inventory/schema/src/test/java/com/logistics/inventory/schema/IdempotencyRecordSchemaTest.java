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

// 앱을 거치지 않고 SQL로 직접 넣어, 같은 키가 두 번 들어오려 해도 DB 제약이 막는지 확인한다
@Testcontainers
class IdempotencyRecordSchemaTest {

    private static final String UNIQUE_VIOLATION = "23505";

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
        execute("TRUNCATE idempotency_record");
    }

    @Test
    @DisplayName("TC-15-04 같은 멱등성 키는 한 행만 들어간다")
    void 같은_키_중복() throws SQLException {
        insert("K1");

        assertThatThrownBy(() -> insert("K1"))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(UNIQUE_VIOLATION));
    }

    private static void insert(final String idempotencyKey) throws SQLException {
        execute("""
                INSERT INTO idempotency_record (idempotency_key, type, product_code, quantity, requester_id)
                VALUES ('%s', 'INBOUND', 'TS-BASIC-BLK-M', 3, 'worker-01')
                """.formatted(idempotencyKey));
    }

    private static void execute(final String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
