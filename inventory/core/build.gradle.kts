base {
    archivesName = "inventory-core"
}

dependencies {
    compileOnly("jakarta.persistence:jakarta.persistence-api")
    compileOnly("org.springframework:spring-context")
    compileOnly("org.springframework:spring-tx")

    testCompileOnly("jakarta.persistence:jakarta.persistence-api")
}
