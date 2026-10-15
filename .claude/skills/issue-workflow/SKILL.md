---
name: issue-workflow
description: 이슈 하나를 처리하는 AI 세션의 실행 절차 (개발자 역할) — 이슈 확인 → 기능 명세서 작성(어떻게 개발할지, PR 계획 포함, docs/specs/ 파일 + 이슈에 승인 요청 코멘트) → 승인 → PR 계획대로 PR마다 브랜치·구현·테스트·셀프 검증·PR 작성(어떻게 개발했는지)·셀프 리뷰·머지 반복 → 마지막 PR로 이슈 종료 → 기록. "#7 시작하자", "이슈 7 작업해줘", "다음 이슈 진행", "명세서 써줘", "다음 PR 진행", "PR 올려줘", "머지해줘", "이 이슈 마무리하자"처럼 이슈 번호가 언급되거나 이슈 단위 개발을 시작·진행·종료하려 할 때 반드시 사용한다.
---

# 이슈 세션 워크플로우

```
Issue(무엇을) ──▶ 기능 명세서(어떻게 개발할지) ── 승인 ──▶ PR 1 … PR n (어떻게 개발했는지)
```

**이슈 하나 = AI 세션 하나.** 이 세션은 하나의 이슈만 다루고, 기능 명세서의 PR 계획대로 PR을 하나 이상 만든다. 마지막 PR이 이슈를 닫는다.

- 규칙 원본(사람용): `docs/conventions/workflow.md`(흐름 · 문서별 양식 · 테스트 규칙), `docs/conventions/git.md`(브랜치 · 커밋 · PR · 머지)
- PR 템플릿: `.github/pull_request_template.md`
- 코드 작성 규칙: `inventory-architecture` skill
- 원본과 이 문서가 다르면 원본이 맞다.
- 적용 범위: GitHub 레포와 이슈가 있는 이후의 모든 개발 작업. 이슈가 없는 작업 요청이 오면 `requirement-refinement` skill로 이슈부터 만들자고 제안한다.

## 멈춰야 하는 지점
이 지점에서는 진행을 멈추고 개발자(사용자)의 답을 기다린다. 사람의 판단이 들어가야 하는 지점이라 AI가 대신 넘어가면 워크플로우가 의미를 잃는다.
1. **기능 명세서 승인 전** — 명세서 파일 외의 코드를 쓰지 않는다
2. **설계 결정이 필요할 때** — 선택지와 트레이드오프를 제시하고 기다린다
3. **각 PR의 머지 전** — 개발자가 머지를 지시할 때만 머지한다

임시 파일(승인 요청 코멘트 · PR 본문 초안)은 `$(git rev-parse --git-dir)/` 아래에 쓴다. worktree마다 따로라 병렬 세션끼리 겹치지 않고, 커밋되지 않는다.

---

## 1단계. 이슈 확인

```bash
gh issue view {N}
```
- 이슈의 요구사항 · 완료 조건 · Open Questions를 확인한다. 상위 요구사항 이슈가 있으면 함께 읽는다.
- 선행 이슈가 끝나지 않았으면 시작하지 않고 알린다.
- Open Questions가 남아 있으면 명세서를 쓰기 전에 먼저 묻는다.
- 비공개 상세 자료(`.pm-plans/`)가 있으면 참고만 하고, 공개 산출물(이슈 · PR · 커밋 · 코드 · docs)에 링크하지 않는다. 경로뿐 아니라 아직 GitHub에 없는 이슈 번호와 로드맵 시점도 쓰지 않는다 (`gh issue list`로 있는 이슈인지 확인).

## 2단계. 기능 명세서 → 승인

이슈가 "무엇을"이라면 명세서는 **"어떻게 개발할지"**다. `docs/conventions/workflow.md` §4 양식을 따른다.

- 위치: `docs/specs/{N}-{영문-설명}.md` (예: `docs/specs/7-outbound.md`). 검증 스크립트가 `docs/specs/{N}-*.md`로 찾는다
- 섹션, TC ID, 검증 방법 규칙은 workflow.md §4 그대로. **PR 계획**은 단독으로 머지 가능한 단위로 나누고, 작은 이슈는 PR 1개로 충분하다

명세서는 **PR 계획의 첫 PR 브랜치**에 커밋한다. 별도의 명세서 PR은 만들지 않는다 — 승인 후 같은 브랜치에서 구현을 이어가고, 명세서는 첫 PR과 함께 main에 들어간다.
```bash
git fetch origin
git switch -c {type}/{N}-{영문-설명} origin/main          # PR 계획 1번의 브랜치 (3-1과 같은 규칙)
git add docs/specs/{N}-{영문-설명}.md
git commit -m "docs({scope}): 기능 명세 작성"
git push -u origin HEAD
gh issue comment {N} --body-file "$(git rev-parse --git-dir)/spec-request-{N}.md"
```
승인 요청 코멘트에는 명세서 링크(브랜치의 파일 URL), PR 계획 표, "결정 필요" 항목만 쓴다. 명세서 본문을 복사하지 않는다.

개발자에게 같은 내용을 알리고 **승인을 기다린다.**
- 승인은 GitHub에 남긴다: 승인 요청 코멘트에 👍 반응 또는 이슈에 "승인" 코멘트. 채팅으로만 승인했다면 GitHub에도 남겨 달라고 요청한다.
- 수정 요청이 오면 명세서 파일을 고쳐 커밋 · push하고, "변경 이력"에 사유를 한 줄 남긴 뒤 다시 승인을 받는다.
- PR 계획이 4개를 넘거나 서로 무관한 목표가 섞여 있으면, 명세서보다 먼저 이슈 분리를 제안한다.

## 3단계. PR 계획대로 반복

명세서의 PR 계획 각각에 대해 아래를 반복한다.

### 3-1. 브랜치
```bash
git fetch origin
git switch -c {type}/{N}-{영문-설명} origin/main     # 예: feat/7-outbound-domain
```
main으로 전환하지 않고 `origin/main`에서 바로 분기한다 (worktree에서도 동작).
첫 PR은 2단계에서 만든 브랜치를 그대로 쓰므로 이 단계를 건너뛴다. 두 번째 PR부터는 첫 PR이 머지된 main에서 분기하므로 명세서가 이미 들어 있다.

### 3-2. 구현
- 이 PR의 범위만 구현하고, 이 PR에 배정된 테스트 케이스를 테스트 코드로 함께 작성한다.
- 테스트 이름에 테스트 케이스 ID를 붙인다.
  ```java
  @Test
  @DisplayName("TC-7-01 재고보다 많이 출고하면 INSUFFICIENT_STOCK 예외가 발생하고 재고는 그대로다")
  void 재고_초과_출고() { ... }
  ```
- 커밋: `{type}({scope}): {한국어 제목}` (이슈 번호를 붙이지 않는다). 커밋마다 빌드가 통과해야 한다.
- 범위 밖 작업이나 버그를 발견하면 지금 하지 말고 적어둔다 (4단계에서 이슈로 제안). TODO 주석을 남기지 않는다.
- 명세서와 다르게 가야 하면 멈추고 알린다. 승인되면 이 PR에서 명세서 파일을 고치고("변경 이력"에 사유), PR의 "명세와 달라진 점"에도 쓴다.

### 3-3. 셀프 검증
PR 본문 초안(`$(git rev-parse --git-dir)/pr-{N}.md`)을 먼저 쓰고 검증한다.
```bash
./gradlew build
S=.claude/skills/issue-workflow/scripts/check-tc-coverage.sh
$S {N} --pr-body "$(git rev-parse --git-dir)/pr-{N}.md"   # 모든 PR: 본문의 TC가 명세서와 코드에 있는지
$S {N}                                                     # 마지막 PR만: 명세서의 TC가 전부 코드에 있는지
```
- 모두 통과해야 PR을 만든다. 코드 변경이 없는 PR은 스크립트 대신 명세서의 검증 방법을 실행하고 결과를 본문에 적는다.
- `src/main`을 바꿨는데 `src/test` 변경이 없으면 CI `test-required`에서 실패한다.
- `inventory-architecture` skill의 "작업 완료 전 체크리스트"를 확인한다.
- 가능하면 `/code-review`로 리뷰하고, 지적을 반영하거나 반영하지 않는 이유를 PR에 남긴다.

### 3-4. PR 작성 — 어떻게 개발했는지
```bash
git push -u origin HEAD
gh pr create --title "{type}({scope}): {제목}" --body-file "$(git rev-parse --git-dir)/pr-{N}.md" \
  --label "$(gh issue view {N} --json labels --jq '[.labels[].name] | join(",")')" --assignee @me
```
`.github/pull_request_template.md`의 모든 섹션을 채운다. 특히:
- 중간 PR은 `Refs #{N}`, 마지막 PR은 `Closes #{N}`
- "구현 내용"은 커밋별이 아니라 **한 일 단위 항목**으로 쓴다 (예: "p6spy 모듈화 및 프로파일 분리", "p6spy 로그 읽기 쉽게 formatting"). 명세서를 반복하지 않는다. 명세서와 다르게 한 부분은 "명세와 달라진 점"에
- **한 일만 적는다.** 실제로 비교하지 않은 대안을 "설계 결정"에 넣지 않는다. 해당 없는 섹션은 "없음"
- PR 본문 초안은 올리기 전에 개발자에게 보여주고 확인받는다
- PR을 연 뒤 수정은 새 커밋으로 올린다. 기존 커밋을 고쳐 force-push하지 않는다 (예외: 3-6의 main rebase)
- **테스트 케이스 표는 필수** — 이 PR이 다룬 TC 전부

### 3-5. 셀프 리뷰 → 머지 대기
판단이 들어간 코드 지점(락 범위, 예외 매핑, 트랜잭션 경계, 규칙과 다르게 한 부분)에 리뷰 코멘트로 근거를 남긴다.
```bash
gh api repos/{owner}/{repo}/pulls/{PR}/comments \
  -f body="…" -f commit_id="$(git rev-parse HEAD)" -f path="{파일}" -F line={줄} -f side=RIGHT
gh pr checks {PR}
```
개발자에게 PR 링크 · 요약 · 확인할 지점을 전달하고 **머지 지시를 기다린다.**

### 3-6. 머지
개발자가 지시한 경우에만:
```bash
gh pr merge {PR} --squash --delete-branch --subject "{PR 제목과 같은 문자열}"
git fetch --prune
```
- 브랜치가 main보다 뒤처져 머지가 막히면 `git rebase origin/main` → `git push --force-with-lease` → CI 재확인 후 다시 머지 대기.

다음 PR이 남아 있으면 3-1로 돌아간다.

## 4단계. 이슈 마무리
- 마지막 PR이 머지되면 이슈가 닫혔는지 확인한다.
- 이 이슈에서 내린 설계 결정 중 리뷰어가 알아야 할 것은 `docs/adr/`에 ADR로 남긴다.
- 3-2에서 적어둔 범위 밖 작업 · 버그를 `requirement-refinement` skill로 새 이슈로 제안한다 (등록은 승인 후).
- 다음 이슈 후보를 알린다.

## 예외 상황
`docs/conventions/workflow.md` §6을 따른다.
