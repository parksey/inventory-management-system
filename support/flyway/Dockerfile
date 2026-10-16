# 빌드 기준 폴더는 레포 루트다 (inventory/schema의 SQL도 복사): docker build -f support/flyway/Dockerfile .
# 앱의 flyway-core(Boot BOM) 버전과 맞춘다
FROM flyway/flyway:12.4.0
COPY support/flyway/src/main/resources/db/migration /flyway/sql
COPY inventory/schema/src/main/resources/db/migration /flyway/sql
CMD ["migrate"]
