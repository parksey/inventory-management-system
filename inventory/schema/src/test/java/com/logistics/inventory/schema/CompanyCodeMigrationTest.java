package com.logistics.inventory.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// 회사 코드가 생기기 전에 쌓인 데이터가 마이그레이션 뒤 기본 회사의 데이터가 되는지 확인한다
@Testcontainers
class CompanyCodeMigrationTest {

    private static final String NOT_NULL_VIOLATION = "23502";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    @DisplayName("TC-40-04 기존 상품과 멱등성 기록은 DEFAULT 회사가 되고, 재고와 원장은 그대로이며, 회사 코드 없는 INSERT는 거절된다")
    void 기존_데이터_기본_회사() throws SQLException {
        flyway("21.1").migrate();
        execute("INSERT INTO product (product_code, name) VALUES ('TS-BASIC-BLK-M', '베이직 반팔 티셔츠 검정 M')");
        execute("INSERT INTO inventory (product_id, quantity) SELECT id, 8 FROM product");
        execute("""
                INSERT INTO stock_transaction (product_id, type, quantity, before_quantity, after_quantity, requester_id)
                SELECT id, 'INBOUND', 8, 0, 8, 'worker-01' FROM product""");
        execute("""
                INSERT INTO idempotency_record (idempotency_key, type, product_code, quantity, requester_id)
                VALUES ('K1', 'INBOUND', 'TS-BASIC-BLK-M', 8, 'worker-01')""");

        flyway("latest").migrate();

        assertThat(queryString("SELECT company_code FROM product")).isEqualTo("DEFAULT");
        assertThat(queryString("SELECT company_code FROM idempotency_record")).isEqualTo("DEFAULT");
        assertThat(queryString("""
                SELECT i.quantity || '/' || t.after_quantity FROM inventory i
                JOIN product p ON p.id = i.product_id JOIN stock_transaction t ON t.product_id = p.id""")).isEqualTo("8/8");
        assertRejected("INSERT INTO product (product_code, name) VALUES ('NEW-01', 'NEW-01')");
        assertRejected("""
                INSERT INTO idempotency_record (idempotency_key, type, product_code, quantity, requester_id)
                VALUES ('K2', 'INBOUND', 'NEW-01', 1, 'worker-01')""");
    }

    private static Flyway flyway(final String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(target)
                .load();
    }

    private static void assertRejected(final String sql) {
        assertThatThrownBy(() -> execute(sql))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(NOT_NULL_VIOLATION));
    }

    private static void execute(final String sql) throws SQLException {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String queryString(final String sql) throws SQLException {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
