base {
    archivesName = "inventory-db"
}

dependencies {
    compileOnly(project(":inventory:core"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")

    testRuntimeOnly(project(":support:flyway"))
}
