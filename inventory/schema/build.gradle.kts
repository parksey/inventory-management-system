base { archivesName = "inventory-schema" }

// 재고 도메인 테이블의 마이그레이션 SQL(src/main/resources/db/migration)만 둔다. Flyway 실행은 support:flyway가 맡는다
dependencies {
    testImplementation(project(":support:flyway"))
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testRuntimeOnly("org.postgresql:postgresql")
}
