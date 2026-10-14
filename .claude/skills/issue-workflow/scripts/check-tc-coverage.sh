#!/usr/bin/env bash
# 기능 명세서의 테스트 케이스 ID(TC-{이슈}-{순번})가 테스트 코드에 존재하는지 확인한다.
#
# 사용법:
#   check-tc-coverage.sh <이슈번호>                     명세서의 모든 TC가 코드에 있는지 (이슈를 닫는 마지막 PR)
#   check-tc-coverage.sh <이슈번호> --pr-body <파일>    PR 본문에 적은 TC가 명세서에 있고 코드에도 있는지 (모든 PR)
#
# 종료 코드: 0 = 통과, 1 = 누락 있음, 2 = 명세서·TC를 찾지 못함
set -eu

issue="${1:?사용법: $0 <이슈번호> [--pr-body <파일>]}"
pr_body=""
if [ "${2:-}" = "--pr-body" ]; then
  pr_body="${3:?--pr-body 다음에 파일 경로가 필요합니다}"
fi

root=$(git rev-parse --show-toplevel)

# 명세서 파일: docs/specs/{이슈번호}-*.md (이슈 하나에 하나)
set -- "$root"/docs/specs/"${issue}"-*.md
if [ ! -f "$1" ]; then
  echo "명세서 파일 docs/specs/${issue}-*.md 를 찾지 못했습니다." >&2
  exit 2
fi
if [ "$#" -gt 1 ]; then
  echo "이슈 #${issue}의 명세서가 여러 개입니다: $*" >&2
  exit 2
fi

extract_ids() { grep -ohE "TC-${issue}-[0-9]+" "$@" | sort -u || true; }

spec_ids=$(extract_ids "$1")
if [ -z "$spec_ids" ]; then
  echo "기능 명세서에 테스트 케이스 ID(TC-${issue}-NN)가 없습니다. 코드 변경이 없는 이슈라면 이 검사는 건너뜁니다." >&2
  exit 2
fi

code_ids=$(find "$root" -path '*/src/test/*' -type f \( -name '*.java' -o -name '*.kt' \) -print0 \
  | xargs -0 grep -ohE "TC-${issue}-[0-9]+" 2>/dev/null | sort -u || true)

if [ -n "$pr_body" ]; then
  targets=$(extract_ids "$pr_body")
  if [ -z "$targets" ]; then
    echo "PR 본문에 테스트 케이스 ID(TC-${issue}-NN)가 없습니다." >&2
    exit 1
  fi
  scope="PR 본문"
else
  targets=$spec_ids
  scope="기능 명세서"
fi

fail=0
report() {  # $1 = 제목, $2 = 대조 대상 ID 목록. targets 중 $2에 없는 ID를 출력
  local missing
  missing=$(comm -23 <(printf '%s\n' "$targets") <(printf '%s\n' "$2"))
  if [ -n "$missing" ]; then
    echo "$1"
    printf '  - %s\n' $missing
    fail=1
  fi
}
report "명세서에 없는 테스트 케이스 ID (명세서를 먼저 갱신하세요):" "$spec_ids"
report "테스트 코드에 없는 테스트 케이스:" "$code_ids"

if [ "$fail" -eq 0 ]; then
  echo "${scope}의 테스트 케이스 $(printf '%s\n' "$targets" | wc -l | tr -d ' ')개가 모두 테스트 코드에 있습니다."
fi
exit "$fail"
