plugins {
    id("org.springframework.boot")
}

dependencies {
    // inventory
    implementation(project(":inventory:core"))
    runtimeOnly(project(":inventory:db"))
    runtimeOnly(project(":inventory:schema"))

    // support
    runtimeOnly(project(":support:flyway"))
    runtimeOnly(project(":support:p6spy"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework:spring-tx")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.named<Jar>("jar") { enabled = false }   // 실행물은 bootJar 하나
