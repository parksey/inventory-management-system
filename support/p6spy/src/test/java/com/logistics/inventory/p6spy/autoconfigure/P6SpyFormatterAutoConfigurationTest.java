package com.logistics.inventory.p6spy.autoconfigure;

import static com.logistics.inventory.p6spy.autoconfigure.P6SpyFormatterAutoConfiguration.LOG_MESSAGE_FORMAT_PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.gavlyukovskiy.boot.jdbc.decorator.DataSourceDecoratorAutoConfiguration;
import com.logistics.inventory.p6spy.PrettySqlFormatter;
import com.p6spy.engine.spy.P6SpyOptions;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * DataSource는 만들기만 하고 연결하지 않으므로 DB 없이 실행된다.
 */
class P6SpyFormatterAutoConfigurationTest {

    private static final String URL = "jdbc:postgresql://localhost:5432/inventory";
    private static final String FORMATTER = PrettySqlFormatter.class.getName();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(P6SpyFormatterAutoConfiguration.class))
            .withUserConfiguration(TestDataSourceConfig.class);

    private final ApplicationContextRunner decoratedDataSourceContextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    P6SpyFormatterAutoConfiguration.class,
                    DataSourceAutoConfiguration.class,
                    DataSourceDecoratorAutoConfiguration.class))
            .withPropertyValues("spring.datasource.url=" + URL);

    @BeforeEach
    void setUp() {
        System.clearProperty(LOG_MESSAGE_FORMAT_PROPERTY);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(LOG_MESSAGE_FORMAT_PROPERTY);
    }

    @Test
    @DisplayName("TC-1-10 decorator 설정이 없으면 기본값이 활성이라 포매터를 지정한다")
    void 설정_없음_지정() {
        contextRunner.run(context -> assertThat(System.getProperty(LOG_MESSAGE_FORMAT_PROPERTY)).isEqualTo(FORMATTER));
    }

    @Test
    @DisplayName("TC-1-11 p6spy 로깅을 꺼도 포매터는 지정한다")
    void 로깅_꺼짐_지정() {
        contextRunner
                .withPropertyValues("decorator.datasource.p6spy.enable-logging=false")
                .run(context -> assertThat(System.getProperty(LOG_MESSAGE_FORMAT_PROPERTY)).isEqualTo(FORMATTER));
    }

    @Test
    @DisplayName("TC-1-12 DataSource 래핑을 끄면 포매터를 지정하지 않는다")
    void 래핑_꺼짐_미지정() {
        contextRunner
                .withPropertyValues("decorator.datasource.enabled=false")
                .run(context -> assertThat(System.getProperty(LOG_MESSAGE_FORMAT_PROPERTY)).isNull());
    }

    @Test
    @DisplayName("TC-1-13 p6spy 스타터가 초기화돼도 포매터가 기본값으로 덮어써지지 않는다")
    void 스타터_초기화_후_유지() {
        decoratedDataSourceContextRunner.run(context -> {
            context.getBean(DataSource.class);   // 래핑을 일으켜 스타터 초기화(p6spy reload)를 거친다
            assertThat(P6SpyOptions.getActiveInstance().getLogMessageFormat()).isEqualTo(FORMATTER);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDataSourceConfig {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(URL);
        }
    }
}
