# Terraform — AWS S3 인프라

이 디렉토리는 애플리케이션(`S3Config.java`, `S3Service.java`)이 사용하는 AWS S3 버킷과,
그 버킷에 접근하는 전용 IAM 사용자를 Terraform으로 관리한다.

## 사전 준비

Terraform CLI를 실행하는 주체(관리자 자격증명)와, Terraform이 **생성하는** 앱 전용 IAM
사용자 자격증명은 서로 다르다.

- 전자는 로컬 `~/.aws/credentials`의 AWS CLI 프로필 또는 `AWS_PROFILE`/`AWS_ACCESS_KEY_ID` 등
  환경변수로 Terraform CLI에 제공한다. 버킷/IAM 리소스를 만들 수 있는 권한이 있는
  IAM 사용자(또는 Role)여야 한다. 이 자격증명은 이 저장소 어디에도 들어오지 않는다.
- 후자(`aws_iam_user.app`)는 이번 `apply`로 새로 발급되며, 애플리케이션이 `.env`를 통해
  런타임에 사용하는 것이다. 아래 3번 절차에서 다룬다.

## 실행 절차

```bash
terraform init
terraform plan -out=tfplan   # 변경 사항을 먼저 파일로 저장해 리뷰한다
terraform apply tfplan
```

`plan` 결과를 파일로 저장한 뒤 리뷰하고 나서 `apply`하는 2단계 흐름을 쓰는 이유:
버킷 이름 오타나 리전 불일치 같은 실수를 apply가 실제로 리소스를 만들기 전에
미리 걸러내기 위함이다.

값은 `terraform.tfvars.example`을 복사해 `terraform.tfvars`로 만든 뒤 채운다
(`terraform.tfvars`는 git에 커밋하지 않는다 — `.gitignore` 참고).

```bash
cp terraform.tfvars.example terraform.tfvars
# terraform.tfvars에서 bucket_name을 전역 고유한 값으로 채운다
```

`bucket_name`은 AWS 전체에서 유일해야 한다. 첫 `apply`에서 `BucketAlreadyExists`가 나면
`terraform.tfvars`의 `bucket_name`을 바꿔 다시 시도한다.

## apply 이후 `.env` 반영

```bash
terraform output -raw s3_bucket_name
terraform output -raw iam_user_access_key_id
terraform output -raw iam_user_secret_access_key
```

위 세 값을 각각 `.env`의 `S3_BUCKET`, `AWS_ACCESS_KEY`, `AWS_SECRET_KEY`에 수동으로 붙여넣는다.
(`.env.example` 참고)

## 트레이드오프 — 알고 있어야 할 것

이 구성은 Terraform state를 **로컬 파일**로 관리하기로 결정했다(remote backend 없음,
1인/소규모 포트폴리오 프로젝트 특성상 채택). 이에 따른 트레이드오프:

- `aws_iam_access_key.app`이 발급하는 IAM secret access key가 로컬 `terraform.tfstate`
  파일에 **평문으로 저장**된다. `terraform.tfstate*`는 `.gitignore`로 git 추적에서
  제외되지만, 로컬 디스크에는 평문으로 남아있다는 점을 인지하고 있어야 한다.
- 포트폴리오를 공개 시연한 뒤에는 `terraform taint aws_iam_access_key.app` 실행 후
  다시 `apply`해 access key를 재발급(rotate)하는 것을 권장한다.
- `terraform output -raw iam_user_secret_access_key`로 시크릿을 터미널에 출력하면
  쉘 히스토리/스크롤백에도 남을 수 있으므로, 값을 `.env`에 옮긴 뒤에는 터미널을
  정리하는 것을 권장한다.

## 버저닝을 켜지 않은 이유

`aws_s3_bucket_versioning`을 `Disabled`로 명시했다. `S3Service.deleteFile()`은 사용자의
명시적 삭제 요청에 대응하는데, 버저닝을 켜면 delete가 실제로는 delete marker만 추가하고
이전 버전이 스토리지에 남아 "삭제하면 진짜 지워진다"는 앱의 기대 동작과 어긋나고 비용만
쌓인다. 또한 업로드 키가 `uploads/{date}/{uuid}.{ext}`로 UUID 기반이라 동일 키 덮어쓰기
충돌 가능성이 사실상 없어, 버저닝이 방어하는 시나리오 자체가 희박하다.

## 파괴(destroy)

```bash
terraform destroy
```

`bucket_force_destroy = false`(기본값)인 상태에서 버킷에 오브젝트가 남아있으면
`destroy`가 실패한다. 이는 실수로 데이터가 함께 삭제되는 것을 막는 의도된
안전장치이며, 정말로 버킷을 비우고 지우고 싶다면 `terraform.tfvars`에서
`bucket_force_destroy = true`로 바꾼 뒤 다시 `apply` → `destroy`한다.
