# 코드 템플릿 — 빌드·모듈 설정

실제 파일이 원본이다. 여기는 구조를 한눈에 보기 위한 요약이다 (Spring Boot 4.1, Gradle 9.8).

---

## 1. Gradle 멀티모듈 설정

```kotlin
// settings.gradle.kts
rootProject.name = "inventory-management-system"
include("app:api", "inventory-domain", "inventory-db", "support:flyway", "support:p6spy")
```

```kotlin
// build.gradle.kts (root)
plugins {
    java
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

configure(subprojects.filter { it.childProjects.isEmpty() }) {   // 컨테이너 프로젝트(:app, :support) 제외
    apply(plugin = "java-library")
    apply(plugin = "io.spring.dependency-management")
    // Java 21 toolchain, Boot BOM import, Lombok, spring-boot-starter-test, -parameters, JUnit Platform
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
    compileOnly(project(":inventory-domain"))     // 실행 시점 domain은 실행 모듈(api)이 넣는다
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    testRuntimeOnly(project(":support:flyway"))   // 테스트 스키마 = 운영 마이그레이션 SQL
    // db 테스트를 추가할 때: testImplementation(project(":inventory-domain")) (compileOnly는 test로 상속되지 않는다), spring-boot-starter-data-jpa-test(@DataJpaTest), spring-boot-testcontainers, testcontainers-postgresql
}
```

```kotlin
// support/flyway/build.gradle.kts — Java 코드 없이 SQL 리소스와 Flyway 의존성만
dependencies {
    api("org.springframework.boot:spring-boot-starter-flyway")   // Boot 4부터 Flyway 자동 설정이 별도 모듈 (flyway-core만으로는 실행되지 않음)
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
}
// SQL 위치: support/flyway/src/main/resources/db/migration/ (파일명 규칙은 docs/conventions/git.md §7)
```

```kotlin
// support/p6spy/build.gradle.kts
dependencies {
    implementation("com.github.gavlyukovskiy:p6spy-spring-boot-starter:2.0.1")   // 2.x = Boot 4 지원 라인
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.hibernate.orm:hibernate-core")   // 포매터만 쓴다. 런타임은 db 모듈이 제공
}
// 포매터 등록: P6SpyFormatterAutoConfiguration (META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports)
//   static BeanFactoryPostProcessor에서 System.setProperty("p6spy.config.logMessageFormat", PrettySqlFormatter)
//   - 스타터는 이미 정의된 값을 덮어쓰지 않는다
```

```kotlin
// app/api/build.gradle.kts — 실행 가능한 jar는 api만 만든다
plugins { id("org.springframework.boot") }

dependencies {
    implementation(project(":inventory-domain"))
    runtimeOnly(project(":inventory-db"))       // 컴파일 시점에는 db 클래스를 볼 수 없다
    runtimeOnly(project(":support:flyway"))     // 현재 모든 프로파일에서 앱 기동 시 마이그레이션 (Helm 도입 시 prod만 끈다)
    runtimeOnly(project(":support:p6spy"))      // 활성 여부는 decorator.datasource.enabled로 프로파일별 제어
    implementation("org.springframework.boot:spring-boot-starter-webmvc")   // Boot 4에서 starter-web의 새 이름
    implementation("org.springframework:spring-tx")   // (에러 응답 이슈에서 추가) 락 타임아웃 등 org.springframework.dao 예외를 핸들러에서 잡기 위해 (DB 구현은 모름)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")   // Testcontainers 2.x 이름
}

tasks.named<Jar>("jar") { enabled = false }   // 실행물은 bootJar 하나
```

Boot 4에서 바뀐 import:
- `@EntityScan` → `org.springframework.boot.persistence.autoconfigure.EntityScan`
- `PostgreSQLContainer` → `org.testcontainers.postgresql.PostgreSQLContainer` (제네릭 없음)

---

## 2. application.yml (api 모듈) — 프로파일별 토글, Flyway 마이그레이션 이미지

설정은 소유자 기준으로 나눈다. **DataSource와 JPA 설정**은 `inventory-db/src/main/resources/inventory-db.yml`에 두고, 실행 단위마다 다른 값(접속 정보, 풀 크기)은 그 안에서 환경변수 placeholder로 받는다. 실행 모듈 `application.yml`에는 웹 계층 설정(`open-in-view`)과 프로파일 토글만 남긴다. db 쪽 파일을 `application.yml`로 만들면 클래스패스에서 api 파일에 가려져 조용히 무시되므로 이름을 달리하고 import한다. `application-db.yml`도 피한다 — `db` 프로파일 파일로 읽힌다.

프로파일은 `local`, `prod` 두 개다. **프로파일을 지정하지 않으면 local**(`spring.profiles.default: local`)이고, **운영은 Helm에서 `SPRING_PROFILES_ACTIVE=prod`로 반드시 명시**한다. 환경 토글(flyway, p6spy)은 공통 파일에 두지 않고 각 프로파일 파일에 명시한다. import한 파일도 프로파일 변형(`inventory-db-prod.yml`)이 자동으로 함께 로드되므로, db 모듈의 운영 전용 값은 거기에 둔다.

```yaml
# inventory-db.yml (inventory-db 모듈)
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/inventory}
    username: ${DB_USERNAME:inventory}
    password: ${DB_PASSWORD:inventory}
    hikari:
      maximum-pool-size: ${DB_POOL_SIZE:10}   # Pod 수 × 이 값 ≤ PostgreSQL max_connections
      connection-timeout: 3000
  jpa:
    hibernate:
      ddl-auto: validate          # 스키마는 flyway가 소유, 앱은 검증만
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
---
# inventory-db-prod.yml (inventory-db 모듈) — 운영은 기본값 없이, 누락 시 로컬 DB로 붙지 않고 기동 실패
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
---
# application.yml (api 모듈, 공통 — 프로파일 없으면 local)
spring:
  profiles:
    default: local
  config:
    import: classpath:inventory-db.yml   # 새 실행 모듈(app/batch 등)도 이 줄을 넣는다
  jpa:
    open-in-view: false
  # flyway는 Boot 기본값(켜짐) — 토글을 두지 않는다
---
# application-prod.yml
decorator:
  datasource:
    enabled: false                # 운영은 DataSource 래핑 자체를 끈다 (jar는 포함돼 있음)
---
# application-local.yml
decorator:
  datasource:
    enabled: true
    p6spy:
      enable-logging: true        # 포맷은 support/p6spy의 AutoConfiguration이 정한다
---
# application-test.yml (src/test/resources) — datasource는 @ServiceConnection이 주입
decorator:
  datasource:
    enabled: true
    p6spy:
      enable-logging: false       # 테스트 로그 노이즈 방지. 디버깅할 때만 true
```

Helm 차트를 도입할 때(아직 아님) `application-prod.yml`에 `spring.flyway.enabled: false`를 넣고 아래 이미지로 hook Job을 만든다.

```dockerfile
# Dockerfile.flyway — Helm pre-install/pre-upgrade Job용 이미지
FROM flyway/flyway:12             # Boot BOM의 flyway-core 메이저 버전과 맞춘다 (로컬·테스트와 운영의 Flyway가 갈라지지 않게)
COPY support/flyway/src/main/resources/db/migration /flyway/sql
# Job 커맨드: flyway -url=... -user=... -password=... migrate
```
