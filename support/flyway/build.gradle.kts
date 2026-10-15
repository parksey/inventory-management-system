// Java 코드 없이 마이그레이션 SQL(src/main/resources/db/migration)과 Flyway 의존성만 둔다.
dependencies {
    api("org.springframework.boot:spring-boot-starter-flyway")   // Boot 4부터 Flyway 자동 설정이 별도 모듈
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testRuntimeOnly("org.postgresql:postgresql")
}
