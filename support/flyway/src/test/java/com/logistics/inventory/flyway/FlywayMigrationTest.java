package com.logistics.inventory.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class FlywayMigrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    @DisplayName("TC-1-14 PostgreSQL에 마이그레이션을 실제로 적용하고 이력 테이블에 기록한다")
    void PostgreSQL_적용() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "spring.flyway.locations=classpath:db/migration-test")
                .run(context -> {
                    final DataSource dataSource = context.getBean(DataSource.class);

                    assertThat(tableExists(dataSource, "flyway_check")).isTrue();
                    assertThat(tableExists(dataSource, "flyway_schema_history")).isTrue();
                    assertThat(context.getBean(Flyway.class).info().current().getVersion().getVersion()).isEqualTo("1");
                });
    }

    private static boolean tableExists(final DataSource dataSource, final String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
             ResultSet tables = connection.getMetaData().getTables(null, "public", table, new String[]{"TABLE"})) {
            return tables.next();
        }
    }
}
