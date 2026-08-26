# 네 루트 모듈(terraform/, prod/, prod-permanent/, loadtest/)의 terraform state를 담는 버킷.
#
# 이 버킷이 왜 이 모듈에 있는가:
#  - 수명이 같다. 이 모듈은 destroy 대상이 아니고 state 버킷도 마찬가지다. 아티팩트 버킷을
#    여기 둔 것과 정확히 같은 기준이다(docs/tasks/cd-pipeline/README.md의 "왜 prod가 아니라
#    prod-permanent인가").
#  - 자기가 만든 버킷에 자기 state를 넣는 순환처럼 보이지만, backend 설정은 apply와 별개
#    단계라 apply -> `terraform init -migrate-state` 순서로 풀린다.
#
# 아티팩트 버킷(s3_artifacts.tf)에 합치지 않는 이유:
#  1) 권한 경계가 다르다. CD의 GitHub Actions 역할은 아티팩트 버킷 ARN으로 한정된
#     PutObject/ListBucket을 갖는다(github_oidc.tf). 버킷을 나눠 두면 그 역할이 state에
#     접근할 수 없다는 것이 자동으로 보장된다.
#  2) 담는 것의 성격이 다르다. 아티팩트는 교체되는 빌드 산출물이고, state는 실제 인프라
#     형상의 기록이다. 잘못 지웠을 때의 복구 비용이 비교가 안 된다.
resource "aws_s3_bucket" "tfstate" {
  bucket = var.tfstate_bucket_name

  # 아티팩트 버킷과 달리 변수로 빼지 않고 false로 박는다. 그쪽은 "언젠가 정리할 수도 있는"
  # 빌드 산출물이지만, 이 버킷은 어떤 경우에도 객체를 남긴 채 삭제돼선 안 된다.
  force_destroy = false

  # 자기 state가 들어 있는 버킷이라 destroy가 성립하지 않는다 — 지우는 순간 그 사실을
  # 기록할 state 자체가 사라진다. force_destroy=false는 "객체가 남아 있으면 실패"지만
  # 이쪽은 plan 단계에서 아예 막아, 실수를 apply 전에 드러낸다.
  lifecycle {
    prevent_destroy = true
  }

  tags = {
    Name = "${var.name_prefix}-tfstate"
  }
}

# state에는 인프라 전체의 형상과 일부 시크릿(예: terraform/prod의 RDS 마스터 패스워드)이
# 들어간다. 공개 노출은 어떤 경우에도 허용되지 않으므로 4개 항목을 모두 차단한다.
resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# 미디어·아티팩트 버킷과 동일한 SSE-S3(AES256). 같은 계정의 다른 프로젝트 state 버킷
# (gilbut-tfstate-*)도 같은 설정이라 계정 전체의 관례와도 일치한다.
#
# 남는 트레이드오프는 알고 있어야 한다 — SSE-S3는 저장 시 암호화까지이고, KMS처럼 "누가
# 복호화할 수 있는가"를 키 정책으로 따로 통제하지는 못한다. terraform/prod의 state에는
# RDS 마스터 패스워드가 평문으로 들어가므로(rds.tf가 SSM에서 읽은 값을 aws_db_instance에
# 넘긴다) 이 사실이 실제로 의미가 있다. 그럼에도 이 규모에서 CMK 관리 비용을 추가할
# 근거가 없다고 판단했다. 자세한 내용은 docs/tasks/tfstate-remote-backend/README.md 참고.
resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 버저닝은 선택이 아니라 필수다 — 로컬 state 시절 terraform이 자동으로 남기던
# `terraform.tfstate.backup`의 역할을 이것이 대체한다. state가 손상되거나 잘못된 apply로
# 덮였을 때 되돌릴 수단이 이것 하나뿐이다.
resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  versioning_configuration {
    status = "Enabled"
  }
}
