# terraform state를 S3 원격 backend로 옮긴다 (#157)

> **결론만 먼저**: 네 모듈의 state를 버킷 하나(`yourtrip-tfstate-520426835144`)의 모듈별 key로 옮겼다. 잠금은 DynamoDB 없이 S3 native lockfile을 쓴다. `.worktreeinclude`의 terraform 항목이 **19개에서 11개로** 줄었고, 사라진 8개가 전부 state다.

## 무엇이 문제였나

state가 로컬 파일에만 있어 **worktree마다 사람이 손으로 복사해야 했다.** 복사를 한 번 빠뜨리면 낡은 state로 apply해 리소스가 중복 생성되거나, `destroy`가 실제 리소스를 놓쳐 과금이 계속되는 drift가 된다. 이 계열의 사고는 이미 [terraform/loadtest/README.md](../../../terraform/loadtest/README.md)에 실제 사례로 기록돼 있었다(App EC2를 콘솔에서 타입 변경해 state가 사라진 인스턴스를 가리키게 된 건).

복사 자체보다 나쁜 것은 **사본이 갈라질 수 있다는 점**이었다. worktree마다 물리적으로 다른 state 파일이 있으면, 두 곳에서 각자 apply해도 충돌이 감지되지 않는다 — 서로 다른 파일이기 때문이다. 원격 backend가 푸는 것은 잠금 이전에 **사본의 존재 자체**다.

## 결정과 근거

### 왜 S3인가

| 후보 | 판단 |
|---|---|
| **S3 backend** | ✅ 이미 같은 계정·리전에서 S3를 세 용도로 쓰고 있고 GitHub OIDC 역할도 있다. state 넷을 합쳐 수십 KB라 비용은 사실상 0 |
| Terraform Cloud / HCP | ❌ AWS 밖의 인증 체계가 하나 늘고, 그 토큰이 다시 `.env`·`.worktreeinclude` 대상이 된다. 없애려는 문제를 형태만 바꿔 되살린다 |
| git 커밋 | ❌ state에는 시크릿이 들어갈 수 있다(아래 트레이드오프) |

**같은 계정에 이미 선례가 있었다.** 다른 프로젝트의 `gilbut-tfstate-520426835144` 버킷이 리전·버저닝·SSE-S3·public access block·키 레이아웃(`<환경>/terraform.tfstate`)까지 이번 결정과 동일하게 구성돼 있었다. 새 방식을 도입한 것이 아니라 **계정 전체의 기존 관례를 이 저장소에 적용한 것**이다. 버킷 이름도 그 관례(`<프로젝트>-tfstate-<계정ID>`)를 따랐다.

### 왜 잠금이 native lockfile인가

조사할 것도 없이 **버전이 결정했다.** 로컬 Terraform이 1.15.8인데, `dynamodb_table` 인자는 1.11에서 deprecated된 뒤 제거됐다. `use_lockfile = true`가 유일한 선택지다. 계정 전체에 DynamoDB 테이블이 0개라는 사실도 이 방향과 맞았다.

그 대가로 `required_version`을 네 모듈 모두 `>= 1.9.0` → `>= 1.11.0`으로 올렸다. 올리지 않으면 **선언한 범위에서는 돌지 않는 구성**이 된다 — 1.9로는 backend 설정이 해석되지 않아 `init`부터 실패한다.

### 왜 버킷 하나 + key 넷인가

잠금이 **key 단위**로 걸리므로 한 버킷이어도 모듈끼리 간섭하지 않는다. 접근제어를 나눠야 하면 prefix 단위 IAM 정책으로 충분하다. 버킷을 넷으로 나누면 버저닝·암호화·정책을 네 벌 관리하게 되는데 얻는 것이 없다.

### 왜 `prod-permanent`가 버킷을 관리하나

아티팩트 버킷과 **수명이 같다.** [docs/tasks/cd-pipeline/](../cd-pipeline/README.md)에서 OIDC 역할을 이 모듈에 둔 것과 정확히 같은 기준이다.

부트스트랩 순환("버킷을 terraform으로 만들면 그 state는 어디에?")은 겉보기일 뿐이다. backend 설정은 apply와 **별개 단계**라 `apply` → `init -migrate-state` 순서로 풀린다.

아티팩트 버킷에 합치지 않은 이유는 **권한 경계**다. CD의 GitHub Actions 역할은 아티팩트 버킷 ARN으로 한정된 `PutObject`/`ListBucket`을 갖는다([github_oidc.tf](../../../terraform/prod-permanent/github_oidc.tf)). 버킷을 나눠 두면 **CD가 state에 접근할 수 없다는 것이 자동으로 보장된다.**

### 왜 backend 설정을 하드코딩해 커밋하나

`backend` 블록은 **변수를 쓸 수 없다.** 대안인 `backend.hcl` 부분 설정은 그 파일을 gitignore해야 해서 복사 대상이 네 개 늘어난다 — 이 작업의 취지에 정면으로 반한다. 계정 ID는 이미 `terraform.tfvars.example`과 문서에 커밋돼 있어 새 노출도 아니다.

같은 이유로 `tfstate_bucket_name` 변수에는 **기본값을 박았다.** tfvars로 빼면 `backend.tf`의 하드코딩과 이중 관리가 되어 어긋날 수 있다. 대조 방법을 남겨 뒀다:

```bash
terraform -chdir=terraform/prod-permanent output -raw tfstate_bucket_name
```

## 남는 트레이드오프

**암호화는 SSE-S3(AES256)다.** 미디어·아티팩트 버킷과 같고, 위 gilbut 선례와도 같다. 다만 이것이 무엇을 하지 *않는지*는 분명히 해 둔다 — 저장 시 암호화까지이고, KMS처럼 "누가 복호화할 수 있는가"를 키 정책으로 따로 통제하지는 못한다.

그리고 **state에는 실제로 시크릿이 들어간다.** [terraform/prod/rds.tf](../../../terraform/prod/rds.tf)가 SSM에서 읽은 RDS 마스터 패스워드를 `aws_db_instance.password`에 넘기므로, `prod` state에는 그 값이 평문으로 남는다. `loadtest`도 같다. 이 규모에서 CMK 관리 비용(월정액 + 요청 과금)을 추가할 근거가 없다고 판단했지만, **판단이지 무해함의 증명은 아니다.**

**자격증명 없이는 아무것도 못 한다.** 예전에는 로컬 state를 읽어 `state list` 정도는 자격증명 없이 됐지만, 이제 backend 초기화 자체가 S3 접근을 요구한다.

**새 worktree에서는 `terraform init`이 필요하다.** `.terraform/`은 복사 대상이 아니기 때문이다. 대신 state 파일을 손으로 복사할 일이 사라졌으니, 사람이 기억해야 할 절차가 "복사 8개"에서 "init 한 번"으로 바뀐 셈이다.

## 로컬 의존 변수(`my_ip_cidr` 등)의 처리 방침

**이번 범위에서는 옮기지 않는다.** state가 원격으로 가도 다음은 `.worktreeinclude`에 그대로 남는다:

| 남는 것 | 이유 |
|---|---|
| `terraform.tfvars` × 4 | 환경별 실제 값. `my_ip_cidr`은 **사람과 시점마다 다른 값**이라 애초에 공유 대상이 아니다 |
| SSH 키페어 2쌍, CloudFront 키페어 | `key_pair.tf`·`cloudfront.tf`가 `file()`로 읽어, 없으면 `plan` 자체가 실패한다 |
| `secrets.local` | SSM이 원본이라 없어도 인프라는 돌지만, worktree마다 다시 조회하게 만들 이유가 없다 |

앞으로 옮길지 판단하는 기준:

- **`my_ip_cidr`은 SSM으로 옮겨도 이득이 없다.** 값이 사람·네트워크마다 달라 공유하면 오히려 틀린다. 이건 "로컬 의존"이 결함이 아니라 성질인 경우다.
- **`rds_password` 같은 비밀값은 이미 SSM이 원본이다.** tfvars에 남은 것은 사본이라, 옮길 대상은 tfvars 자체가 아니라 *tfvars를 거치는 경로*다.
- 따라서 다음 작업이 생긴다면 대상은 "tfvars 전체의 SSM 이관"이 아니라 **`file()`로 읽는 키페어를 어떻게 다룰지**다. 그쪽이 `plan` 실패를 직접 유발하는 유일한 항목이다.

## 실측 — 마이그레이션 결과

`loadtest` → `prod` → `prod-permanent` → `terraform/`(루트) 순으로 옮겼다. 앞의 둘은 당시 리소스 0(destroy 상태)이라 실패해도 잃을 것이 없어 절차 검증용으로 먼저 돌렸다.

| # | 확인 | 결과 |
|---|---|---|
| V1 | state가 원격에 있다 | ✅ `root/`·`prod-permanent/` 두 키 생성. **`prod`·`loadtest`는 객체가 생기지 않았다** — 리소스 0인 빈 state는 원격에 쓰이지 않는다. 다음 apply 시점에 생성된다 |
| V2 | 리소스를 잃지 않았다 | ✅ 루트는 전후 `state list` **차이 0**. `prod-permanent`는 이번에 추가한 버킷 리소스 4개만 증가(15 → 19) |
| V3 | 형상이 바뀌지 않았다 | ✅ `prod-permanent` `No changes`. 루트는 변경이 잡혔으나 **마이그레이션과 무관한 기존 drift**였다(아래) |
| V4 | 잠금이 동작한다 | ✅ apply 중 `.tflock` 객체(253B) 생성 확인. 동시 apply가 `Error acquiring the state lock` / **`StatusCode: 412`** 로 거부됨 |
| V5 | 수동 복사가 사라졌다 | ✅ 로컬 state 파일을 전부 무력화한 뒤에도 `state list`가 원격에서 정상 동작(루트 19, prod-permanent 19) |
| V6 | 버저닝이 백업을 대체한다 | ✅ 객체 버전 누적 확인 |

### V3에서 발견한 것 — CloudFront 공개키의 줄바꿈 drift

루트 모듈 `plan`이 `aws_cloudfront_public_key.signer must be replaced`를 냈다. 원인은 **줄바꿈이었다**:

| | 길이 | CR |
|---|---|---|
| state의 `encoded_key` | 178 | 없음(LF) |
| 로컬 `cloudfront_public_key.pem` | 182 | 있음(CRLF) |

CR을 제거하면 두 값은 **완전히 동일**하다. 즉 키 자체는 같은데 줄바꿈만 달라 `forces replacement`가 걸린 것이다.

**마이그레이션이 원인이 아니다** — 마이그레이션 이전 백업(`terraform.tfstate.pre-migrate-bak`)의 값도 178자·CR 없음으로 동일했다. worktree와 메인의 `.pem` 해시도 같아, 이 drift는 **어느 워킹트리에서 apply해도 재현된다.**

이 상태로 루트 모듈에 apply하면 CloudFront 서명 키가 교체되고 **기존에 발급된 Signed URL이 전부 무효화된다.** 이번 작업 범위 밖이라 고치지 않았고, 별도 이슈로 다룬다.

## 로컬 state 파일의 처리

마이그레이션 후에도 로컬 `terraform.tfstate`는 남는다. 이것을 **삭제하지 않고 `.migrated-to-s3` 접미사를 붙여 무력화**했다(worktree와 메인 워킹트리 양쪽).

삭제 대신 이름 변경을 택한 이유:

- terraform이 읽지 못하게 되므로, **backend.tf가 아직 없는 브랜치에서 실수로 apply하는 것**을 막는다. 이 PR이 머지되기 전 메인 워킹트리는 `dev`를 보고 있었고, 그쪽 로컬 state는 이미 낡은 상태였다 — 정확히 이 이슈가 없애려던 사고 조건이다.
- 파일이 보존되므로 롤백이 가능하다.

`.gitignore`의 `*.tfstate.*` 패턴이 이 이름을 이미 잡으므로 커밋에는 들어가지 않는다. [docs/guide/worktree.md](../../guide/worktree.md)의 PR 체크리스트 필터에도 같은 패턴을 추가했다.

## 롤백

모듈 단위로 독립적으로 되돌릴 수 있다.

1. 해당 모듈의 `backend.tf` 삭제
2. `terraform init -migrate-state -force-copy` (원격 → 로컬 역방향)
3. 그래도 어긋나면 `terraform.tfstate.pre-migrate-bak`을 원래 이름으로 복구

버킷 자체는 `prevent_destroy = true` 때문에 terraform으로 지워지지 않는다. 되돌리려면 그 설정을 먼저 풀어야 한다 — **의도된 마찰**이다.

## 참고

| 문서 | 무엇이 있나 |
|---|---|
| [docs/guide/worktree.md](../../guide/worktree.md) | 무엇이 아직 복사 대상인지, PR 전 체크리스트 |
| [docs/tasks/cd-pipeline/](../cd-pipeline/README.md) | 원격 backend가 없던 시절 SSM 간접 참조를 택한 근거(해소 각주 포함) |
| [terraform/prod-permanent/s3_tfstate.tf](../../../terraform/prod-permanent/s3_tfstate.tf) | 버킷 정의와 각 설정의 근거 |
| [terraform/backend.tf](../../../terraform/backend.tf) | backend 설정과 하드코딩 근거 |
