# Git 컨벤션

브랜치, 커밋, 이슈, PR 규칙의 원본. 작업 흐름 전체는 [workflow.md](workflow.md)를 본다.

---

## 1. 브랜치 전략

```
main ──────────────────────●──▶   배포 기준. dev에서 배포할 때만 합친다
                          /
dev  ──●────────●────────●──▶   기본 작업 브랜치
        \      / \      /
         ●──●─┘   ●──●─┘
   feat/6-inbound-api   feat/7-outbound-api
```

| 규칙 | 내용 |
|---|---|
| 기본 브랜치 | `dev`. 모든 작업 브랜치는 dev에서 나와 dev로 PR을 보낸다 |
| 배포 브랜치 | `main`. 배포할 때 dev → main으로 PR을 보낸다 |
| 작업 브랜치 | `{type}/{이슈번호}-{영문-설명}` — 항상 `origin/dev`에서 분기. 한 이슈에서 PR을 여러 개 낼 때는 PR마다 브랜치를 새로 만든다 (`feat/7-outbound-domain`, `feat/7-outbound-api`) |
| 예시 | `feat/7-outbound-api`, `chore/1-multimodule-setup`, `fix/21-lock-timeout` |
| dev, main 직접 push | 하지 않는다. 예외: main에 push되면 CI가 배포할 이미지 태그(`infra/helm/inventory/values.yaml`)를 바꾸는 커밋을 main에 직접 push한다 |

이슈 번호만 쓰고 `#`을 붙이지 않는 이유: 셸에서 `#`이 주석으로 해석돼 매번 따옴표가 필요해진다.

---

## 2. 커밋 메시지

```
{type}({scope}): {제목}

{본문 — 선택. 무엇을 했는지가 아니라 왜 했는지}
```

| type | 용도 |
|---|---|
| `feat` | 새 기능 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변경 없는 구조 개선 |
| `test` | 테스트 추가, 보강 |
| `docs` | 문서 |
| `chore` | 빌드, 설정, CI, 배포 (Docker, Helm) |

- scope: `product` `inventory` `transaction` `common` `api` `db` `flyway` `infra` `harness` 등 변경 영역. `api`는 `app` 모듈(Controller, 응답 형식, 로깅 설정), `infra`는 compose, Dockerfile, Helm, CI
- 제목: 한국어, 50자 이내, 마침표 없음
- 한 커밋 = 한 논리적 변경. 커밋마다 빌드, 테스트가 통과해야 한다

```
✅ feat(inventory): 출고 시 재고 부족 검증 추가
✅ test(inventory): 동시 출고 시 초과 출고가 없는지 검증
❌ 수정
❌ feat: 출고 API, 에러 처리, 테스트 추가     ← 여러 변경이 섞임
```

---

## 3. 이슈

이슈는 **무엇을** 할지만 쓴다. 어떻게 할지는 기능 명세서(`docs/specs/`), 어떻게 했는지는 PR에 쓴다 ([workflow.md §1](workflow.md#1-큰-그림)).

| 템플릿 | 용도 | 라벨 |
|---|---|---|
| 기능 요구사항 | PM이 받은 기능 요청 — 하위 이슈를 묶는 상위 이슈 | `requirement` |
| 기능 개발 | 없던 기능을 만든다 | `feat` |
| 기능 수정 | 있는 기능의 동작을 바꾼다 | `feat` (구조만 바꾸면 `refactor`) |
| 버그 수정 | 의도와 다르게 동작하는 것을 고친다 | `fix` |
| 작업 | 빌드, CI, 배포, 문서 등 기능 외 작업 | `chore` `docs` `refactor` |

| 라벨 | 값 |
|---|---|
| 유형 | 위 표 |
| 우선순위 | `P0` 필수, `P1` 권장, `P2` 여유 시 |

- 제목: `{type}({scope}): {무엇을}` — 예: `feat(inventory): 상품 출고`. 요구사항 이슈는 `[요구사항] {기능}` — 예: `[요구사항] 재고 출고`
- 구체화로 나온 이슈는 요구사항 이슈의 **sub-issue**로 연결한다.
- 기능 수정 이슈의 커밋 type은 동작이 바뀌면 `feat`, 구조만 바뀌면 `refactor`를 쓴다.

---

## 4. PR

PR은 **어떻게 개발했는지**를 쓴다. 한 이슈에서 기능 명세서의 PR 계획에 따라 PR이 여러 개 나올 수 있다.

| 항목 | 규칙 |
|---|---|
| 제목 | 커밋 형식과 동일 — `feat(inventory): 출고 도메인 로직 구현` |
| 본문 | PR 템플릿의 모든 섹션 작성. 한 일만 적고, 해당 없는 섹션은 "없음". **테스트 케이스 표 필수** ([workflow.md §5](workflow.md#5-테스트-규칙)) |
| 이슈 연결 | 중간 PR은 `Refs #N`, 이슈를 끝내는 마지막 PR은 `Closes #N` |
| 라벨과 담당자 | 이슈와 같은 라벨, 담당자는 본인 |
| PR 연 뒤 수정 | 새 커밋으로 올린다. 기존 커밋을 고쳐 force-push하지 않는다 (예외: dev rebase) |
| 셀프 리뷰 | 머지 전 "Files changed"에서 판단이 들어간 지점에 리뷰 코멘트로 근거를 남긴다 |
| 머지 조건 | CI 통과 (빌드, 테스트), 최신 dev 반영, 개발자의 머지 지시 |

---

## 5. 머지와 정리

- **merge commit**으로 합친다. `git log --first-parent dev`로 보면 PR 하나당 커밋 하나이고, 세부 커밋은 두 번째 부모 쪽에 그대로 남는다.
- 머지할 때 커밋 제목을 PR 제목으로 직접 지정한다. GitHub 기본값은 `Merge pull request #N from ...`이라 무슨 변경인지 보이지 않는다.
  ```bash
  gh pr merge {PR} --merge --delete-branch --subject "feat(inventory): 출고 도메인 로직 구현"
  ```
- 머지 후 원격 브랜치는 자동 삭제된다. 로컬은 `git fetch --prune`으로 정리한다.
- 다음 작업은 항상 `origin/dev`에서 분기한다: `git fetch origin && git switch -c {브랜치} origin/dev` (worktree에서도 동작)
- 브랜치가 dev보다 뒤처지거나 충돌하면 `git rebase origin/dev` 후 `git push --force-with-lease`
- dev → main(배포) PR도 merge commit으로 합친다. squash하면 dev와 main의 이력이 갈라진다

---

## 6. 보호 규칙 (dev, main) — 아직 적용하지 않음

| 설정 | 이유 |
|---|---|
| PR을 통해서만 변경 | 모든 변경이 리뷰 가능한 단위로 기록된다 |
| CI 통과 필수 | 깨진 코드가 dev, main에 들어가지 않는다 |
| 최신 기준 브랜치에서 통과 필수 | 각자는 통과했지만 합치면 깨지는 상황을 막는다 |

---

## 7. Flyway 버전 규칙

`V{이슈번호}_{순번}__{설명}.sql` — 예: `V3_1__create_inventory_schema.sql`

병렬 브랜치가 각자 마이그레이션을 추가해도 버전이 겹치지 않게 하려는 규칙이라 Git 규칙에 포함한다. 이미 머지된 마이그레이션 파일은 수정하지 않고, 변경은 새 파일로 추가한다.
