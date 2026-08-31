# Terraform — AWS S3 / CloudFront 인프라

이 디렉토리는 애플리케이션(`S3Config.java`, `S3Service.java`, `CloudFrontService.java`)이
사용하는 AWS S3 버킷, CloudFront 배포, 그리고 이들에 접근하는 전용 IAM 사용자를
Terraform으로 관리한다.

## CloudFront Signed URL용 키페어 생성 (`terraform apply` 전에 먼저 진행)

mycourse의 비공개 장소 이미지는 CloudFront Signed URL로 서빙된다. 이 서명에 쓰는
키페어는 Terraform이 아니라 **로컬에서 직접 생성**한다 — 개인키가 절대 tfstate에
들어가지 않게 하기 위함이다(Terraform은 공개키만 `aws_cloudfront_public_key`로 등록한다).

키 알고리즘은 **ECDSA P-256**을 쓴다. CloudFront trusted key group이 지원하는 키는
RSA-2048 또는 ECDSA P-256 두 가지뿐인데(`docs/tasks/TASK-CLOUDFRONT.md` 참고), ECDSA
P-256이 RSA-2048급보다 안전 강도가 높으면서도 서명 연산이 훨씬 가볍다. (과거 RSA-2048
방식을 쓰던 절차는 `openssl genrsa -out cloudfront_private_key.pem 2048` /
`openssl rsa -pubout -in cloudfront_private_key.pem -out cloudfront_public_key.pem`이었다 —
롤백이 필요하면 이 명령으로 되돌릴 수 있다.)

```bash
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:prime256v1 -out cloudfront_private_key.pem
openssl ec -in cloudfront_private_key.pem -pubout -out cloudfront_public_key.pem
```

`openssl ecparam -genkey`가 아니라 `openssl genpkey`를 쓰는 이유: `ecparam -genkey`는 개인키를
SEC1 형식("BEGIN EC PRIVATE KEY")으로 출력하는데, AWS SDK(`software.amazon.awssdk:cloudfront`)의
PEM 파서가 이 헤더를 인식하지 못해 `NullPointerException`(`PemObjectType.ordinal()` on null)이
발생하는 것을 `CloudFrontServiceTest`로 실제 확인했다. `genpkey`는 PKCS8 형식("BEGIN PRIVATE
KEY")으로 출력하며, SDK가 이 형식은 RSA/EC 구분 없이 정상 로드한다.

- `cloudfront_public_key.pem` → 이 디렉토리에 두고 `terraform.tfvars`의
  `cloudfront_public_key_path`가 가리키게 한다(`terraform.tfvars.example` 참고).
- `cloudfront_private_key.pem` → **레포에 두지 않는다.** 애플리케이션이 실행되는 서버의
  임의 경로에 두고, 그 경로를 `.env`의 `CLOUDFRONT_PRIVATE_KEY_PATH`에 채운다.
- 두 파일 모두 `.gitignore`에 이미 걸린 `*.pem` 패턴이 없다면 커밋 방지를 위해
  `.gitignore`에 `*.pem`을 추가해둘 것.

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
terraform output -raw cloudfront_domain_name
terraform output -raw cloudfront_distribution_id
terraform output -raw cloudfront_key_pair_id
```

위 값들을 각각 `.env`의 `S3_BUCKET`, `AWS_ACCESS_KEY`, `AWS_SECRET_KEY`, `CLOUDFRONT_DOMAIN`,
`CLOUDFRONT_DISTRIBUTION_ID`, `CLOUDFRONT_KEY_PAIR_ID`에 수동으로 붙여넣는다. `CLOUDFRONT_PRIVATE_KEY_PATH`는
위 "CloudFront Signed URL용 키페어 생성" 절차에서 만든 `cloudfront_private_key.pem`의 실제 경로를 채운다.
(`.env.example` 참고)

## CloudFront invalidation 과금

`S3Service.deleteFile()`은 이미지 삭제 시 CloudFront invalidation을 함께 호출한다
(`terraform/iam.tf`의 `cloudfront:CreateInvalidation` 권한 참고). AWS는 월 1,000개 경로까지
무료이고, 그 이상은 경로당 과금된다 — 이미지 삭제가 아주 빈번한 서비스가 아니라면 일반적인
사용량에서는 무료 한도 내로 충분하다.

## 트레이드오프 — 알고 있어야 할 것

Terraform state는 S3 원격 backend에 있다(#157, `yourtrip-tfstate-520426835144`의 `root/`).
아래는 그와 별개로 이 모듈이 발급하는 IAM 자격증명에 따라오는 트레이드오프다:

- `aws_iam_access_key.app`이 **새로 발급되는 순간** 그 secret access key가 로컬
  `terraform.tfstate`에 평문으로 저장된다. `terraform.tfstate*`는 `.gitignore`로 git 추적에서
  제외되지만, 로컬 디스크에는 평문으로 남는다는 점을 인지하고 있어야 한다.
- **다만 현재 state에는 그 값이 없다** — `secret`·`encrypted_secret`·`iam_user_secret_access_key`
  output이 모두 비어 있고, 남아 있는 것은 access key ID(`AKIA…`)뿐이다. 키 리소스가 어느 시점에
  import된 것으로 보이며, AWS API는 **생성 시점 이후로 secret을 돌려주지 않아** 한 번 잃으면
  복구되지 않는다. 따라서:
  - `terraform output -raw iam_user_secret_access_key`는 **지금 실행하면 실패한다.** 운영 중인
    값의 유일한 사본은 `.env`에 있다.
  - 아래의 재발급을 하면 새 secret이 state에 실리므로, 위 첫 줄의 트레이드오프가 그 시점부터
    다시 유효해진다.
- 포트폴리오를 공개 시연한 뒤에는 `terraform apply -replace=aws_iam_access_key.app`으로
  access key를 재발급(rotate)하는 것을 권장한다(`terraform taint`는 0.15.2에서 deprecated됐다).
  재발급하면 **`.env`의 값도 함께 갱신해야 한다** — 앱은 이 정적 자격증명으로만 S3에 접근한다.
- 재발급 후 `terraform output -raw iam_user_secret_access_key`로 시크릿을 터미널에 출력하면
  쉘 히스토리/스크롤백에도 남을 수 있으므로, 값을 `.env`에 옮긴 뒤에는 터미널을
  정리하는 것을 권장한다.

## 버저닝을 켜지 않은 이유

`aws_s3_bucket_versioning`을 `Disabled`로 명시했다. `S3Service.deleteFile()`은 사용자의
명시적 삭제 요청에 대응하는데, 버저닝을 켜면 delete가 실제로는 delete marker만 추가하고
이전 버전이 스토리지에 남아 "삭제하면 진짜 지워진다"는 앱의 기대 동작과 어긋나고 비용만
쌓인다. 또한 업로드 키가 UUID 기반(`uploads/{date}/{uuid}.{ext}`,
`private/{courseId}/{uuid}.{ext}`)이라 동일 키 덮어쓰기 충돌 가능성이 사실상 없어, 버저닝이
방어하는 시나리오 자체가 희박하다.

> 다만 버저닝이 꺼져 있다는 것은 **삭제된 오브젝트를 복구할 수단이 없다**는 뜻이기도 하다.
> 대량 삭제(예: prefix 단위 정리)를 수행할 때는 사전 백업 여부를 반드시 별도로 판단해야 한다.

## 파괴(destroy)

```bash
terraform destroy
```

`bucket_force_destroy = false`(기본값)인 상태에서 버킷에 오브젝트가 남아있으면
`destroy`가 실패한다. 이는 실수로 데이터가 함께 삭제되는 것을 막는 의도된
안전장치이며, 정말로 버킷을 비우고 지우고 싶다면 `terraform.tfvars`에서
`bucket_force_destroy = true`로 바꾼 뒤 다시 `apply` → `destroy`한다.
