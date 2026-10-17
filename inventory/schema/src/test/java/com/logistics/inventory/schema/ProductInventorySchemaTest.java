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
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// 앱을 거치지 않고 SQL로 직접 넣어, 앱 검증이 없어도 DB 제약이 막는지 확인한다
@Testcontainers
class ProductInventorySchemaTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

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
    }

    @Test
    @DisplayName("TC-7-05 같은 상품 코드로 상품을 두 번 넣으면 유니크 제약이 거절한다")
    void 상품_코드_중복() throws SQLException {
        execute("INSERT INTO product (product_code, name) VALUES ('TS-BASIC-BLK-M', '베이직 반팔 티셔츠 검정 M')");

        assertRejected("INSERT INTO product (product_code, name) VALUES ('TS-BASIC-BLK-M', '다른 이름')", UNIQUE_VIOLATION);
    }

    @Test
    @DisplayName("TC-7-06 재고 수량을 음수로 바꾸거나 넣으면 체크 제약이 거절하고 수량은 그대로다")
    void 음수_수량() throws SQLException {
        execute("INSERT INTO product (product_code, name) VALUES ('TS-BASIC-BLK-M', '베이직 반팔 티셔츠 검정 M')");
        execute("INSERT INTO product (product_code, name) VALUES ('TS-BASIC-BLK-L', '베이직 반팔 티셔츠 검정 L')");
        execute("INSERT INTO inventory (product_id, quantity) SELECT id, 0 FROM product WHERE product_code = 'TS-BASIC-BLK-M'");

        assertRejected("UPDATE inventory SET quantity = -1", CHECK_VIOLATION);
        assertRejected("INSERT INTO inventory (product_id, quantity) SELECT id, -1 FROM product WHERE product_code = 'TS-BASIC-BLK-L'",
                CHECK_VIOLATION);
        assertThat(queryLong("SELECT quantity FROM inventory")).isZero();
        assertThat(queryLong("SELECT count(*) FROM inventory")).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-7-07 없는 상품을 가리키는 재고를 넣으면 외래 키 제약이 거절한다")
    void 없는_상품의_재고() {
        assertRejected("INSERT INTO inventory (product_id, quantity) VALUES (999, 0)", FOREIGN_KEY_VIOLATION);
    }

    private static void assertRejected(final String sql, final String sqlState) {
        assertThatThrownBy(() -> execute(sql))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(sqlState));
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
