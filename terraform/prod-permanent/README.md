# terraform/prod-permanent — 운영 도메인·인증서·배포 아티팩트

운영 인프라 중 **한 번 만들고 계속 유지하는 것**만 담는 루트 모듈이다. 실제 서버(ALB·ASG·RDS·ElastiCache)는 [../prod/](../prod/README.md)에 있고 그쪽은 온디맨드로 apply/destroy를 반복한다.

| 담는 것 | 왜 여기인가 |
|---|---|
| Route53 호스티드존 | destroy하면 NS 세트가 바뀌어 **가비아 콘솔에서 네임서버를 다시 입력**하고 전파를 다시 기다려야 한다 |
| ACM 인증서 + DNS 검증 레코드 | 검증 CNAME이 존에 남아 있으면 자동 갱신된다. 매번 재발급하면 apply마다 검증 대기가 붙는다 |
| 배포 아티팩트 S3 버킷 | 서버가 내려가 있어도 빌드 산출물은 남아 있어야 한다 |
| GitHub Actions OIDC provider + 배포 역할 | **서버가 내려가 있어도 CD는 돌아야 한다.** 운영은 온디맨드라 대부분의 기간 `../prod/`가 없는데, 그때도 dev 머지마다 JAR을 S3에 올리는 것까지는 계속돼야 한다. 역할을 `../prod/`에 두면 destroy 직후부터 AssumeRole이 실패해 워크플로가 빨간불이 된다 |

> ⚠️ **이 모듈은 destroy 대상이 아니다.** 서버를 내릴 때는 `terraform -chdir=../prod destroy`만 실행한다. 여기까지 destroy하면 도메인 위임부터 다시 해야 한다.

## state를 왜 나눴는가

리소스마다 수명이 다르기 때문이다. 근거와 경계 설정의 전체 논의는 [docs/tasks/prod-infra-iac/README.md](../../docs/tasks/prod-infra-iac/README.md)의 "설계 원칙 1"에 있다.

**ALB alias 레코드는 여기가 아니라 `../prod/`에 있다.** ALB는 apply마다 새로 만들어져 DNS명이 바뀌는데, 레코드가 같은 state에서 `aws_lb.this.dns_name`을 참조하면 자동으로 새 ALB를 가리킨다. 레코드를 여기 두면 매번 손으로 고쳐야 한다.

## 사전 준비

1. **가비아에서 `.com` 도메인을 구매한다.**
2. AWS 자격증명을 확인한다. 이 저장소의 프로파일은 `default`가 아니라 **`terraform-admin`**이다.
   ```bash
   export AWS_PROFILE=terraform-admin
   aws sts get-caller-identity
   ```
3. `terraform.tfvars`를 만든다.
   ```bash
   cp terraform.tfvars.example terraform.tfvars
   ```
   `domain_name`에 구매한 도메인을, `artifact_bucket_name`에 전역 유일한 버킷 이름을 넣는다.

## 실행 순서 — apply가 두 번으로 나뉜다

네임서버 위임이 중간에 끼기 때문이다. **이 순서를 지키지 않으면 3단계에서 75분을 기다렸다가 실패한다.**

### 1단계 — 호스티드존만 먼저 만든다

```bash
terraform init
terraform apply -target=aws_route53_zone.this
terraform output nameservers
```

`-target`을 쓰는 것은 이 저장소에서 예외적인 조작이지만, 여기서는 **위임 없이는 다음 리소스가 성립하지 않는다**는 순서 제약 때문이라 정당하다.

### 2단계 — 가비아에 네임서버를 입력한다 (수동)

가비아 콘솔 → 도메인 관리 → **네임서버 설정**에 위 출력의 4개 값을 입력한다. 끝에 붙는 점(`.`)은 넣어도 되고 빼도 된다.

전파를 **반드시 확인한 뒤** 다음으로 넘어간다.

```bash
dig NS <domain> @8.8.8.8 +short
```

출력이 `ns-xxx.awsdns-xx.com` 형태의 AWS 네임서버여야 한다. 가비아 기본 네임서버(`ns.gabia.co.kr` 등)가 나오면 아직 전파되지 않은 것이다. 보통 10분~1시간, 길면 48시간까지 걸린다.

### 3단계 — 인증서와 버킷을 만든다

```bash
terraform plan -out=tfplan
terraform apply tfplan
```

`aws_acm_certificate_validation`이 검증 완료까지 apply를 붙잡는다. 정상이면 수 분 내에 끝난다.

```bash
aws acm describe-certificate \
  --certificate-arn "$(terraform output -raw acm_certificate_arn)" \
  --query 'Certificate.Status' --output text
```

`ISSUED`가 나와야 한다.

## 출력값을 다음 모듈로 옮긴다

이 저장소는 `terraform_remote_state`를 쓰지 않고 **출력을 손으로 tfvars에 옮기는** 방식을 쓴다(기존 `terraform/` → `terraform/loadtest/` 관계와 동일하다).

```bash
terraform output -raw route53_zone_id
terraform output -raw acm_certificate_arn
terraform output -raw artifact_bucket_name
```

세 값을 `../prod/terraform.tfvars`에 넣는다.

CD 역할 ARN은 tfvars가 아니라 **GitHub 저장소 변수**로 간다. 비밀이 아니므로 `secret`이 아니라 `variable`이고, **이 워크플로에 `secrets` 참조가 하나도 없다는 사실 자체가 산출물이다**(#120).

```bash
gh variable set AWS_ROLE_ARN --body "$(terraform output -raw github_actions_role_arn)"
gh variable set ARTIFACT_BUCKET --body "$(terraform output -raw artifact_bucket_name)"
```

## 알아둬야 할 것

- **Route 53 Registrar에서 도메인을 샀다면 `route53.tf`를 고쳐야 한다.** 그 경우 호스티드존이 등록과 동시에 자동 생성되므로, `resource`가 아니라 `data "aws_route53_zone"`으로 기존 존을 읽어야 한다. 그러지 않으면 NS가 다른 두 번째 존이 생기고 **레코드는 들어가는데 외부 조회는 안 되는** 형태로 DNS가 조용히 실패한다.
- **인증서 리전은 `ap-northeast-2`다.** ALB는 리전 리소스라 인증서도 같은 리전이어야 한다. CloudFront용 인증서가 `us-east-1`을 요구하는 것과 규칙이 다르므로 혼동하지 않는다.
- **호스티드존은 존재만으로 월 $0.50이 과금된다.** 서버를 내려도 이 비용은 계속 나간다 — 도메인을 유지하는 값이다.
- **GitHub OIDC provider는 계정당 하나만 존재할 수 있다.** 다른 프로젝트가 이미 만들어 뒀다면 apply가 `EntityAlreadyExists`로 실패한다. 그때는 기존 ARN을 `github_oidc_provider_arn` 변수에 채우면 새로 만들지 않고 재사용한다(`aws iam list-open-id-connect-providers`로 찾는다).
- **CD 역할의 신뢰 정책은 `dev` 브랜치로 못박혀 있다.** 롤백용 `workflow_dispatch`도 반드시 `dev`를 선택해 실행해야 한다 — dispatch의 `sub` 클레임은 이벤트 종류가 아니라 **선택한 ref**로 결정되기 때문이다. 다른 브랜치를 고르면 AssumeRole이 거부된다.
- `terraform.tfstate`·`terraform.tfvars`는 `.gitignore` 대상이며, worktree에서 작업했다면 [CLAUDE.md](../../CLAUDE.md)의 worktree 규칙에 따라 메인 워킹트리 사본도 갱신해야 한다.
