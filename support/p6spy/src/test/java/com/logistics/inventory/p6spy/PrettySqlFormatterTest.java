package com.logistics.inventory.p6spy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PrettySqlFormatterTest {

    private static final String URL = "jdbc:postgresql://localhost:5432/inventory";

    private final PrettySqlFormatter formatter = new PrettySqlFormatter();

    private static boolean hasLine(final String message, final String strippedLine) {
        return message.lines().anyMatch(line -> line.strip().equals(strippedLine));
    }

    @Test
    @DisplayName("TC-1-02 SELECT는 절마다 줄을 나누고 바인딩 값과 실행 시간을 함께 출력한다")
    void select_정렬() {
        final String message = formatter.formatMessage(
                1, "2026-10-15 10:00:00", 17, "statement",
                "select * from product where sku = ?",
                "select * from product where sku = 'SKU-001' order by name",
                URL);

        assertThat(hasLine(message, "select")).as(message).isTrue();
        assertThat(hasLine(message, "from")).as(message).isTrue();
        assertThat(hasLine(message, "where")).as(message).isTrue();
        assertThat(hasLine(message, "order by")).as(message).isTrue();
        assertThat(message).contains("'SKU-001'", "Execution Time: 17 ms");
    }

    @Test
    @DisplayName("TC-1-03 SQL이 없는 commit 이벤트는 카테고리 이름만 출력한다")
    void commit_카테고리_출력() {
        final String message = formatter.formatMessage(1, "", 0, "commit", "", "", "");

        assertThat(message.strip()).isEqualTo("commit");
    }

    @Test
    @DisplayName("TC-1-04 left join 키워드는 한 줄에 유지된다")
    void join_한_줄() {
        final String sql = "select * from product p left join inventory i on p.id = i.product_id";

        final String message = formatter.formatMessage(1, "2026-10-15 10:00:00", 4, "statement", sql, sql, URL);

        assertThat(hasLine(message, "left join")).as(message).isTrue();
        assertThat(hasLine(message, "left")).as(message).isFalse();
        assertThat(hasLine(message, "join")).as(message).isFalse();
    }

    @Test
    @DisplayName("TC-1-05 create DDL은 DDL 포매터로 출력한다")
    void create_ddl() {
        final String sql = "create table product (id bigint not null, name varchar(200), primary key (id))";

        final String message = formatter.formatMessage(1, "2026-10-15 10:00:00", 9, "statement", sql, sql, URL);

        assertThat(message).contains("create table product", "Execution Time: 9 ms");
    }

    @Test
    @DisplayName("TC-1-06 drop DDL은 DDL 포매터로 출력한다")
    void drop_ddl() {
        final String sql = "drop table product";

        final String message = formatter.formatMessage(1, "2026-10-15 10:00:00", 2, "statement", sql, sql, URL);

        assertThat(message).contains("drop table product", "Execution Time: 2 ms");
    }

    @Test
    @DisplayName("TC-1-07 alter DDL은 DDL 포매터로 출력한다")
    void alter_ddl() {
        final String sql = "alter table product add column description varchar(100)";

        final String message = formatter.formatMessage(1, "2026-10-15 10:00:00", 5, "statement", sql, sql, URL);

        assertThat(message).contains("alter table", "Execution Time: 5 ms");
    }

    @Test
    @DisplayName("TC-1-08 comment DDL은 DDL 포매터로 출력한다")
    void comment_ddl() {
        final String sql = "comment on column product.name is '상품명'";

        final String message = formatter.formatMessage(1, "2026-10-15 10:00:00", 1, "statement", sql, sql, URL);

        assertThat(message).contains("comment on", "Execution Time: 1 ms");
    }

    @Test
    @DisplayName("TC-1-09 문자열 리터럴 안의 SQL 키워드는 그대로 둔다")
    void 리터럴_보존() {
        final String message = formatter.formatMessage(
                1, "2026-10-15 10:00:00", 3, "statement",
                "select * from product where name = ?",
                "select * from product where name = 'left join order by from update set'",
                URL);

        assertThat(message).contains("'left join order by from update set'");
    }
}
