base {
    archivesName = "inventory-erp"
}

// 외부 ERP 호출(밖으로 나가는 어댑터). db처럼 domain의 포트(ErpClient)를 구현하고, app은 실행 시점에만 넣는다
dependencies {
    compileOnly(project(":inventory:core"))   // 실행 시점 domain은 app이 넣는다
    implementation("org.springframework:spring-context")
    implementation("org.slf4j:slf4j-api")

    testImplementation(project(":inventory:core"))
}
