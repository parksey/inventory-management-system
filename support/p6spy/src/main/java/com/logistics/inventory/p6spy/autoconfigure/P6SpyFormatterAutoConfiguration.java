package com.logistics.inventory.p6spy.autoconfigure;

import com.logistics.inventory.p6spy.PrettySqlFormatter;
import com.p6spy.engine.spy.P6SpyOptions;
import com.p6spy.engine.spy.appender.MessageFormattingStrategy;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * p6spy 로그 포매터 지정한다.
 */
@AutoConfiguration
@ConditionalOnClass({DataSource.class, P6SpyOptions.class, MessageFormattingStrategy.class})
@ConditionalOnProperty(prefix = "decorator.datasource", name = "enabled", havingValue = "true", matchIfMissing = true)
public class P6SpyFormatterAutoConfiguration {

    static final String LOG_MESSAGE_FORMAT_PROPERTY = "p6spy.config.logMessageFormat";

    @Bean
    static BeanFactoryPostProcessor p6spyLogMessageFormat() {
        return beanFactory -> System.setProperty(LOG_MESSAGE_FORMAT_PROPERTY, PrettySqlFormatter.class.getName());
    }
}
