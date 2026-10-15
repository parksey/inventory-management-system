dependencies {
    compileOnly(project(":inventory-domain"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")

    testRuntimeOnly(project(":support:flyway"))
}
