package com.logistics.inventory.p6spy;

import com.p6spy.engine.logging.Category;
import com.p6spy.engine.spy.appender.MessageFormattingStrategy;
import java.util.List;
import java.util.Locale;
import org.hibernate.engine.jdbc.internal.FormatStyle;

/**
 * p6spy SQL 로그를 Hibernate FormatStyle로 정렬해 출력하는 포매터.
 */
public class PrettySqlFormatter implements MessageFormattingStrategy {

    private static final String NEW_LINE = "\n";
    private static final String TAB = "\t";
    private static final int FOOTER_WIDTH = 50;
    private static final List<String> DDL_PREFIXES = List.of("create", "alter", "drop", "comment");

    @Override
    public String formatMessage(
            final int connectionId,
            final String now,
            final long elapsed,
            final String category,
            final String prepared,
            final String sql,
            final String url
    ) {
        if (sql == null || sql.isBlank()) {
            return formatCommand(category);
        }
        return formatSql(category, sql) + footer(elapsed);
    }

    private String formatSql(final String category, final String sql) {
        final FormatStyle style = isStatementDdl(category, sql) ? FormatStyle.DDL : FormatStyle.BASIC;
        return NEW_LINE + style.getFormatter().format(sql);
    }

    private boolean isStatementDdl(final String category, final String sql) {
        if (!Category.STATEMENT.getName().equals(category)) {
            return false;
        }
        final String normalized = sql.trim().toLowerCase(Locale.ROOT);
        return DDL_PREFIXES.stream().anyMatch(normalized::startsWith);
    }

    private String footer(final long elapsed) {
        final String executionTime = " Execution Time: " + elapsed + " ms";
        final int dashCount = Math.max(0, FOOTER_WIDTH - executionTime.length());
        return NEW_LINE + NEW_LINE + "-".repeat(dashCount) + executionTime + NEW_LINE;
    }

    private String formatCommand(final String category) {
        if (category == null || category.isBlank()) {
            return "";
        }
        return NEW_LINE + TAB + category + NEW_LINE;
    }
}
