package com.logistics.inventory.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// 앱을 거치지 않고 SQL로 직접 넣어, 앱 버그로 수치가 틀린 원장이 와도 DB 제약이 막는지 확인한다
@Testcontainers
class StockTransactionSchemaTest {

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
        execute("TRUNCATE stock_transaction, inventory, product RESTART IDENTITY");
        execute("INSERT INTO product (company_code, product_code, name) VALUES ('COMPANY-A', 'TS-BASIC-BLK-M', '베이직 반팔 티셔츠 검정 M')");
    }

    @ParameterizedTest(name = "{0}, 수량 {1}, 전 {2}, 후 {3}")
    @CsvSource({
            "INBOUND, 0, 5, 5",       // 수량 0
            "INBOUND, 3, -1, 2",      // 변경 전 음수
            "INBOUND, 3, 5, 9",       // 후 != 전 + 수량
            "OUTBOUND, 3, 8, 6",      // 후 != 전 - 수량
            "MOVE, 3, 5, 8"           // 알 수 없는 구분
    })
    @DisplayName("TC-12-01 수치가 맞지 않는 원장은 체크 제약이 거절한다")
    void 수치가_틀린_원장(final String type, final long quantity, final long before, final long after) {
        assertThatThrownBy(() -> insert(type, quantity, before, after))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(CHECK_VIOLATION));
    }

    @ParameterizedTest(name = "{0}, 수량 {1}, 전 {2}, 후 {3}")
    @CsvSource({
            "INBOUND, 3, 5, 8",
            "OUTBOUND, 3, 8, 5"
    })
    @DisplayName("TC-12-02 수치가 맞는 입고, 출고 원장은 저장된다")
    void 수치가_맞는_원장(final String type, final long quantity, final long before, final long after) throws SQLException {
        insert(type, quantity, before, after);

        assertThat(queryLong("SELECT count(*) FROM stock_transaction")).isEqualTo(1);
    }

    private static void insert(final String type, final long quantity, final long before, final long after) throws SQLException {
        execute("""
                INSERT INTO stock_transaction (product_id, type, quantity, before_quantity, after_quantity, requester_id)
                SELECT id, '%s', %d, %d, %d, 'worker-01' FROM product
                """.formatted(type, quantity, before, after));
    }

    private static void execute(final String sql) throws SQLException {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long queryLong(final String sql) throws SQLException {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
