# Inventory Management System

상품의 입고 · 출고 · 재고 조회를 제공하는 재고 관리 API.
여러 서버(Pod)가 동시에 요청을 처리해도 재고 정합성이 깨지지 않는 것을 목표로 한다.


## 기술 스택
Java 21 · Spring Boot · Spring Data JPA · PostgreSQL · Flyway · Gradle(Kotlin DSL)

## 모듈 구조

| 모듈 | 역할 |
|---|---|
| `app` | 실행 모듈. 도메인 조립, Controller, 요청과 응답 DTO, 예외 응답 |
| `inventory/core` | 엔티티, VO, Repository 인터페이스, 서비스 |
| `inventory/db` | Repository 구현 (JPA, native query) |
| `inventory/schema` | 재고 도메인 테이블 마이그레이션 SQL |
| `support/flyway` | Flyway 실행 설정, 공용 기초 데이터 |
| `support/p6spy` | 로컬 SQL 로깅 (운영에서는 꺼짐) |

## 개발 프로세스
- [작업 흐름](docs/conventions/workflow.md) — 요구사항 → 이슈 → 기능 명세서 → PR
- [Git 컨벤션](docs/conventions/git.md) — 브랜치 · 커밋 · PR 규칙
- 기능 명세서: `docs/specs/` — 이슈마다 어떻게 개발할지 (개발 시작 시 작성)
