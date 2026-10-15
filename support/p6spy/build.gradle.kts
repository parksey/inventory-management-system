// 어노테이션과 포매터만 컴파일 시점에 쓴다. 런타임은 api(spring)와 db(hibernate)가 제공한다.
dependencies {
    implementation("com.github.gavlyukovskiy:p6spy-spring-boot-starter:2.0.1")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("jakarta.annotation:jakarta.annotation-api")
    compileOnly("org.hibernate.orm:hibernate-core")

    testImplementation("org.hibernate.orm:hibernate-core")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testRuntimeOnly("org.postgresql:postgresql")
}
