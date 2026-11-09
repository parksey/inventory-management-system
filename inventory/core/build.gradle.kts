base {
    archivesName = "inventory-core"
}

dependencies {
    compileOnly("jakarta.persistence:jakarta.persistence-api")
    compileOnly("org.springframework:spring-context")
    compileOnly("org.springframework:spring-tx")
    compileOnly("org.hibernate.orm:hibernate-core")   // payload JSONB 매핑(@JdbcTypeCode)용 어노테이션만

    testCompileOnly("jakarta.persistence:jakarta.persistence-api")
    testCompileOnly("org.hibernate.orm:hibernate-core")
}
