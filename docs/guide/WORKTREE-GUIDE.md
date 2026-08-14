# worktree 작업 가이드 — gitignore된 파일 다루기

이 저장소는 `git worktree`로 작업 브랜치를 분리해서 쓴다. 그런데 실행에 꼭 필요한 파일 상당수(`.env`, terraform state·키, AI 지침 등)가 `.gitignore` 대상이라 **git으로는 worktree 간에 공유되지 않는다.** 이 공백을 [`.worktreeinclude`](../../.worktreeinclude) 목록과 `post-checkout` 훅이 메운다.

이 문서는 그 메커니즘의 동작 방식과, 그로 인해 실제로 겪은 함정들을 정리한다.

## 용어 — "상위 브랜치"라고 부르지 않는다

이 문서가 다루는 복사는 **브랜치가 아니라 디렉터리 사이의 파일 복사**다. git과 무관한 파일시스템 작업이라, 브랜치 용어로 부르면 반드시 오해가 생긴다.

| 용어 | 가리키는 것 | 실제 경로 |
|---|---|---|
| **main working tree** (메인 워킹트리) | 저장소를 처음 clone한 원본 디렉터리. 여기 아래에 worktree들이 들어 있다 | `C:\YOURTRIP\YOURTRIP_BE\YOURTRIP_BE` |
| **linked working tree** (연결 워킹트리) | `git worktree add`로 만든 작업 디렉터리 | `<메인>\.claude\worktrees\<이름>` |

**"상위 브랜치"·"부모 브랜치"라는 표현은 쓰지 않는다.** 그렇게 쓰면 `dev`/`main` 같은 base 브랜치나 upstream 브랜치로 읽혀서, "dev에 커밋해서 반영한다"는 전혀 다른 행동으로 이어진다. 실제로 해야 하는 일은 **메인 워킹트리 디렉터리에 파일을 복사하는 것**이며, 대상 파일이 `.gitignore`되어 있어 커밋으로는 옮길 수 없다는 게 이 문서의 출발점이다.

셸에서 두 경로를 얻는 방법(훅이 쓰는 것과 동일):

```bash
MAIN="$(cd "$(git rev-parse --git-common-dir)/.." && pwd)"   # 메인 워킹트리
WT="$(git rev-parse --show-toplevel)"                        # 지금 있는 워킹트리
```

## 동작 방식

`post-checkout` 훅(`.git/hooks/post-checkout`)이 `.worktreeinclude`의 각 줄을 읽어 메인 워킹트리에서 지금 워킹트리로 복사한다. 핵심은 세 가지다.

1. **단방향이다** — 메인 워킹트리 → linked worktree. 반대 방향은 없다.
2. **덮어쓰지 않는다** — 대상 경로가 이미 존재하면(`[ ! -e "$DST_PATH" ]`) 건너뛴다.
3. **메인 워킹트리에서는 아무 일도 하지 않는다** — `MAIN_DIR == TARGET_DIR`이면 즉시 종료한다.

즉 **실질적으로 "새 worktree를 만드는 순간 1회"만 동작한다.**

2번이 중요한 이유: `post-checkout`은 이름 그대로 **모든 체크아웃에서 실행된다.** `git worktree add`뿐 아니라 worktree 안에서 `git checkout`/`git switch`로 브랜치를 옮길 때마다 돈다. 만약 덮어쓰기로 동작했다면, 작업 중 브랜치를 잠깐 옮기는 것만으로 수정해 둔 `.env`나 `terraform.tfstate`가 메인 것으로 덮여 날아간다. 이 가드는 **로컬 상태를 보호하는 장치**이지 불필요한 제약이 아니다.

그 대가로 "메인의 갱신이 기존 worktree에 전파되지 않는다"(함정 2)가 생기는데, 이건 덮어쓰기로 풀 문제가 아니라 아래 체크리스트로 사람이 확인할 문제다.

## 반드시 알아야 할 함정 4가지

### 1. worktree에서 수정한 gitignore 파일은 어디에도 전파되지 않는다

`.env`나 `CLAUDE.md`, `.claude/rules/*.md`를 worktree에서 고쳐도 **메인 저장소와 다른 worktree는 모른다.** git이 추적하지 않으니 커밋으로도 옮겨지지 않고, 훅은 단방향이라 역방향 복사도 없다.

→ **이런 파일을 고쳤으면 메인 저장소 사본도 직접 갱신해야 한다.** 그러지 않으면 다음에 만드는 worktree는 옛 버전을 받는다.

### 2. 메인에서 고쳐도 기존 worktree에는 반영되지 않는다

훅이 덮어쓰지 않기 때문에, 이미 그 파일을 갖고 있는 worktree는 계속 옛 버전을 쓴다.

실제 사례: 메인의 `.env`에 `NAVER_CLIENT_ID`·`OPENAI_API_KEY`가 추가됐지만, 8/5에 만든 worktree는 그 키들이 없는 채로 남아 있었다. 새 환경변수를 요구하는 코드가 머지되면 그 worktree에서만 기동이 실패한다.

→ 다른 worktree에서 새 설정을 추가했다는 걸 알게 되면, 쓰기 전에 메인과 비교한다.

### 3. 새로 생긴 파일은 등록해야 다음 worktree가 받는다

`terraform init`이 만드는 `.terraform.lock.hcl`처럼, 작업 중 새로 생기는 gitignore 파일이 있다. `.worktreeinclude`에 추가하지 않으면 다음 worktree는 그 파일 없이 시작한다.

### 4. 훅은 git으로 공유되지 않는다

훅은 `.git/hooks/post-checkout`에 있고 **git 추적 대상이 아니다.** 즉 저장소를 새로 clone한 사람에게는 훅이 없어서 `.worktreeinclude`만 받고 **복사 자체가 동작하지 않는다.** 지금은 사실상 1인이 쓰는 구조라 드러나지 않지만, 훅을 `scripts/hooks/`에 두고 `core.hooksPath`로 가리키게 하면 git으로 공유할 수 있다.

이 때문에 훅의 동작이 환경마다 다를 수 있다는 점도 유의한다. 아래 "읽는 목록" 관련 수정도 이 저장소의 로컬 훅에만 적용돼 있다.

> **[해소됨] 훅이 메인의 목록을 읽던 문제**
>
> 원래 훅은 `$MAIN_DIR/.worktreeinclude`를 읽었다. 이 파일은 git 추적 대상이라 브랜치마다 내용이 다른데, 그래서 worktree에서 목록을 고쳐 커밋해도 **메인 워킹트리가 다른 브랜치를 보고 있으면 반영되지 않았다.** 실제로 `.claude/` 항목을 좁힌 커밋을 `dev`에 넣었지만 메인이 `dev-ai-course`를 보고 있어 옛 목록이 쓰이고 있었다.
>
> 지금은 **대상 워킹트리의 목록을 우선 읽도록** 고쳤다(없으면 메인으로 폴백). `.worktreeinclude`는 추적 파일이라 checkout 시점에 대상에도 이미 존재하고, "지금 이 브랜치에 필요한 파일 목록"이라는 의미에도 그쪽이 맞다.
>
> ```sh
> WORKTREE_INCLUDE="$TARGET_DIR/.worktreeinclude"
> [ -f "$WORKTREE_INCLUDE" ] || WORKTREE_INCLUDE="$MAIN_DIR/.worktreeinclude"
> ```

## 목록에 넣을지 판단하는 기준

`.worktreeinclude`에 **넣어야 하는 것**:

- **재생성이 불가능한 것** — `terraform.tfstate`(이미 apply된 실제 인프라의 유일한 진실 공급원), SSH·CloudFront 키페어, `terraform.tfvars`(환경별 실제 값)
- **재생성은 되지만 그러면 의미가 깨지는 것** — `.terraform.lock.hcl`. `terraform init`으로 다시 만들 수는 있으나 그 시점의 최신 provider를 새로 고르므로, "어느 worktree에서 apply해도 같은 버전으로 재현된다"는 lock의 목적이 사라진다
- **모든 worktree에서 동일해야 하는 설정** — `.env`, `CLAUDE.md`, `.claude/rules/`, `.claude/settings.json`

**넣지 말아야 하는 것**:

- **재생성으로 충분한 캐시** — `.gradle/`, `build/`, `out/`, `bin/`, `.terraform/`(플러그인 캐시). 복사 비용만 낭비다
- **절대경로가 박힌 것** — `.idea/`. 다른 경로의 worktree에 복사하면 오히려 깨진 설정이 된다
- **그 worktree에서만 의미 있는 것** — `.claude/plans/`. 계획 파일은 해당 작업의 맥락 문서라, 통째로 복사하면 남의 계획서가 새 worktree마다 딸려가고 같은 파일명이 여러 곳에 중복된다
- **자기 자신을 포함하는 경로** — `.claude/worktrees/`. 여기에 모든 worktree가 들어 있어 복사하면 worktree 안에 다른 worktree 전체가 중첩된다(실제로 `.claude/worktrees/A/.claude/worktrees/B/...` 형태가 관측됐다)

디렉터리를 통째로 지정하기 전에 **그 안에 위 "넣지 말아야 할 것"이 섞여 있지 않은지** 확인한다. `.claude/`가 정확히 그 사례였고, 지금은 `rules/`와 `settings.json`만 개별 지정한다.

## PR을 올리기 전 체크리스트

**이 확인은 PR 생성 직전에 한다.** worktree는 PR을 올린 뒤 정리(삭제)되는 경우가 많은데, 그때까지 메인 워킹트리에 반영하지 않은 gitignore 파일은 **worktree와 함께 사라진다.** 커밋에 포함되지 않으니 되돌릴 방법도 없다.

아래 한 덩어리를 실행하면 1·3번이 한 번에 확인된다:

```bash
MAIN="$(cd "$(git rev-parse --git-common-dir)/.." && pwd)"
WT="$(cd "$(git rev-parse --show-toplevel)" && pwd)"
[ "$MAIN" = "$WT" ] && echo "메인 워킹트리 — 확인 불필요" || {

# 목록은 지금 worktree의 .worktreeinclude를 읽는다(훅과 동일한 기준).
# 메인 워킹트리는 다른 브랜치를 체크아웃하고 있을 수 있어 목록이 옛 버전일 수 있다.
LIST="$(grep -vE '^\s*#|^\s*$' "$WT/.worktreeinclude" | tr -d '\r')"

echo "== 1) 목록에 없는 gitignore 파일 (등록할지 판단) =="
git status --ignored --short | grep '^!!' | sed 's/^!! //' | while IFS= read -r p; do
  # 재생성되는 캐시·산출물, 개인/환경 전용 설정은 애초에 복사 대상이 아니다
  # ("넣지 말아야 하는 것" 참고). .gitignore 항목과 대조해 유지한다.
  case "$p" in
    # 빌드·캐시 산출물
    .gradle/|build/|out/|bin/|dist/|.apt_generated/|.sts4-cache/) continue;;
    # IDE 설정 — 절대경로가 박혀 있어 다른 경로의 worktree에서는 오히려 깨진다
    .idea/|.vscode/|.settings/|.classpath|.project|.factorypath|.springBeans) continue;;
    *.iml|*.iws|*.ipr) continue;;
    nbproject/|nbbuild/|nbdist/|.nb-gradle/) continue;;
    # terraform 플러그인 캐시·plan 산출물·로컬 오버라이드(그 환경 전용)
    .terraform/|*/.terraform/|*.tfplan|tfplan*|*/tfplan*) continue;;
    override.tf|override.tf.json|*_override.tf|*_override.tf.json) continue;;
    */override.tf|*/override.tf.json|*/*_override.tf|*/*_override.tf.json) continue;;
    .terraformrc|terraform.rc|*/.terraformrc|*/terraform.rc) continue;;
    # state 백업 — terraform이 자동 생성하는 타임스탬프 백업과 손으로 뜬 백업.
    # 목록에 있는 표준 terraform.tfstate.backup은 [0-9] 조건 덕분에 걸리지 않는다.
    *.tfstate.[0-9]*.backup|*/*.tfstate.[0-9]*.backup) continue;;
    *.pre-import-*|*/*.pre-import-*|*.bak-[0-9]*|*/*.bak-[0-9]*) continue;;
    # 로그·부하테스트 결과(k6 --summary-export 산출물)
    *.log|*.output|results/|*/results/) continue;;
  esac
  # 이미 목록에 있거나(하위 포함), 목록 항목의 상위 디렉터리로 뭉쳐 나온 경우 제외
  skip=0
  while IFS= read -r inc; do
    [ -z "$inc" ] && continue
    case "$p"   in "$inc"|"$inc"/*) skip=1; break;; esac
    case "$inc" in "$p"*)           skip=1; break;; esac
  done <<EOF
$LIST
EOF
  [ $skip -eq 1 ] && continue
  echo "  검토 필요: $p"
done
echo "  (출력 없으면 새로 챙길 것 없음)"

echo "== 2) 목록에 있는데 메인과 내용이 다른 것 =="
printf '%s\n' "$LIST" | while IFS= read -r f; do
  [ -e "$WT/$f" ] || continue
  if [ ! -e "$MAIN/$f" ]; then echo "  MISSING in main : $f"
  elif ! diff -qr "$MAIN/$f" "$WT/$f" >/dev/null 2>&1; then echo "  DIFFERENT       : $f"; fi
done
}
```

**①의 필터가 하는 일**: `git status --ignored`는 gitignore된 것을 **전부** 나열해서(이 저장소 기준 24줄) 그대로 두면 `.gradle/`·`build/`·`.idea/` 같은 산출물과 이미 등록된 항목이 뒤섞여 매번 눈으로 걸러야 한다. 그래서 위 "넣지 말아야 하는 것" 기준을 `case` 패턴으로 넣고, `.worktreeinclude`에 이미 있는 항목도 제외했다. **출력이 비어 있는 게 정상 상태**이고, 뭔가 찍히면 그때만 판단하면 된다.

필터 목록은 `.gitignore`와 대조해 유지한다. 특히 실수하기 쉬운 것들:

- **`results/`** — k6가 `--summary-export=results/...`로 남기는 부하테스트 결과. 로컬에서 k6를 한 번만 돌려도 생긴다
- **`*.tfplan` / `tfplan*`** — `terraform plan -out=tfplan`의 산출물([terraform/loadtest/README.md](../../terraform/loadtest/README.md)의 표준 절차에 있다)
- **`override.tf` 계열, `.terraformrc`** — terraform 로컬 오버라이드. 그 환경 전용이라 오히려 복사하면 안 된다
- **state 백업** — terraform이 자동으로 만드는 `*.tfstate.<timestamp>.backup`과 손으로 뜬 `*.pre-import-*`. 목록에 있는 표준 `terraform.tfstate.backup`은 패턴의 `[0-9]` 조건 덕분에 걸러지지 않는다

`.gitignore`에 새 항목이 추가됐는데 그게 산출물·캐시 성격이라면 이 필터에도 함께 넣는다. 그러지 않으면 매번 "검토 필요"로 떠서 진짜 신호를 가린다.

한 가지 한계: `git status --ignored --short`는 디렉터리를 뭉쳐서 출력한다(`.claude/` 한 줄). 그래서 `.claude/` 안에 새 파일이 생겨도 개별로는 드러나지 않는다. 목록에 부분만 등록된 디렉터리(`.claude/rules/`, `.claude/settings.json`)의 내부를 확인하려면 `git status --ignored=matching --short .claude/`처럼 경로를 좁혀서 다시 본다.

출력에 따라:

1. **새 gitignore 파일이 있으면** — "넣어야 하는 것" 기준에 해당하는지 판단하고, 해당하면 `.worktreeinclude`에 등록한다. 이 파일은 git 추적 대상이라 PR에 함께 담기면 된다
2. **`MISSING in main` / `DIFFERENT`가 나오면** — 메인 워킹트리 사본을 갱신한다. 어느 쪽이 최신인지 먼저 확인할 것 — worktree가 최신인 경우(내가 고친 것)와 메인이 최신인 경우(다른 worktree에서 추가된 설정)가 둘 다 있다

   ```bash
   diff "$MAIN/<경로>" "$WT/<경로>"      # 무엇이 다른지 먼저 본다
   cp "$WT/<경로>" "$MAIN/<경로>"        # worktree가 최신일 때
   ```

3. **`.env`처럼 비밀값이 든 파일**은 diff 출력을 그대로 붙여넣지 말고 키 이름만 비교한다

   ```bash
   diff <(grep -oE '^[A-Z_]+=' "$MAIN/.env" | sort) <(grep -oE '^[A-Z_]+=' "$WT/.env" | sort)
   ```

## 참고

- [.worktreeinclude](../../.worktreeinclude) — 실제 목록. 각 항목을 포함/제외한 이유가 주석에 있다
- `.git/hooks/post-checkout` — 훅 구현(git 추적 대상이 아니므로 로컬에만 존재)
- [terraform/loadtest/README.md](../../terraform/loadtest/README.md) — 부하테스트 인프라의 state·키 파일이 왜 재생성 불가능한지
