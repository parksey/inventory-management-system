# 코드 템플릿 — 빌드·모듈 설정

---

## 1. Gradle 멀티모듈 설정

```kotlin
// settings.gradle.kts
rootProject.name = "inventory"
include("api", "inventory-domain", "inventory-db", "flyway")
```

```kotlin
// build.gradle.kts (root)
plugins {
    java
    id("org.springframework.boot") version "3.x.x" apply false   // ⚠ 버전 확정(DECISIONS A6) 시 갱신. Boot 4면 flyway 의존성·@EntityScan 패키지가 바뀐다
    id("io.spring.dependency-management") version "1.x.x" apply false
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "io.spring.dependency-management")

    java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports { mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES) }
    }

    dependencies {
        "compileOnly"("org.projectlombok:lombok")
        "annotationProcessor"("org.projectlombok:lombok")
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    }
}
```

```kotlin
// inventory-domain/build.gradle.kts — Spring Data·웹·DB 드라이버 없음
dependencies {
    compileOnly("jakarta.persistence:jakarta.persistence-api")
    // compileOnly("org.hibernate.orm:hibernate-core")  // @Immutable 등 Hibernate 어노테이션이 필요할 때만
    compileOnly("org.springframework:spring-context")   // 어노테이션만 쓴다. 런타임은 api가 제공
    compileOnly("org.springframework:spring-tx")
}
```

```kotlin
// inventory-db/build.gradle.kts
dependencies {
    implementation(project(":inventory-domain"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    testRuntimeOnly(project(":flyway"))           // 테스트 스키마 = 운영 마이그레이션 SQL
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
}
```

```kotlin
// flyway/build.gradle.kts — Java 코드 없이 SQL 리소스와 Flyway 의존성만
dependencies {
    api("org.flywaydb:flyway-core")                        // Boot 4: spring-boot-starter-flyway 로 교체
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
}
// SQL 위치: flyway/src/main/resources/db/migration/ (파일명 규칙은 docs/conventions/git.md §7)
```

```kotlin
// api/build.gradle.kts — 실행 가능한 jar는 api만 만든다
plugins { id("org.springframework.boot") }

dependencies {
    implementation(project(":inventory-domain"))
    runtimeOnly(project(":inventory-db"))          // 컴파일 시점에는 db 클래스를 볼 수 없다
    runtimeOnly(project(":flyway"))                // 실행 여부는 spring.flyway.enabled로 프로파일별 제어
    runtimeOnly("com.github.gavlyukovskiy:p6spy-spring-boot-starter:1.x.x")   // 활성 여부는 decorator.datasource.enabled로 프로파일별 제어
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework:spring-tx")   // 락 타임아웃 등 org.springframework.dao 예외를 핸들러에서 잡기 위해 (DB 구현은 모름)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}
```

---

## 2. application.yml (api 모듈) — 프로파일별 토글, Flyway 마이그레이션 이미지

```yaml
# application.yml (공통 = 운영 기준)
spring:
  jpa:
    hibernate:
      ddl-auto: validate          # 스키마는 flyway가 소유, 앱은 검증만
    open-in-view: false
  flyway:
    enabled: false                # 운영은 Helm hook Job이 마이그레이션
decorator:
  datasource:
    enabled: false                # 운영은 DataSource 래핑 자체를 끈다 (jar는 포함돼 있음)
---
# application-local.yml
spring:
  flyway:
    enabled: true                 # 로컬은 앱 기동 시 마이그레이션
decorator:
  datasource:
    enabled: true
    p6spy:
      enable-logging: true
      log-format: "%(category) %(executionTime)ms%n%(sql)"   # 포맷터 클래스 없이 starter 설정으로
```

```dockerfile
# Dockerfile.flyway — Helm pre-install/pre-upgrade Job용 이미지
FROM flyway/flyway:10             # Boot BOM의 flyway-core 메이저 버전과 맞춘다 (로컬·테스트와 운영의 Flyway가 갈라지지 않게)
COPY flyway/src/main/resources/db/migration /flyway/sql
# Job 커맨드: flyway -url=... -user=... -password=... migrate
```
