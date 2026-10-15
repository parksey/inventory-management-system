plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":inventory-domain"))
    runtimeOnly(project(":inventory-db"))       // 컴파일 시점에는 db 클래스를 볼 수 없다
    runtimeOnly(project(":support:flyway"))     // 실행 여부는 spring.flyway.enabled로 프로파일별 제어

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.named<Jar>("jar") { enabled = false }   // 실행물은 bootJar 하나
