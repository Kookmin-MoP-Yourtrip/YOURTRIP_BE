# ASG 인스턴스가 부팅 시 내려받을 배포 JAR을 두는 버킷.
#
# 부하테스트 환경은 로컬 빌드 후 scp로 JAR을 넣었지만(terraform/loadtest/README.md), ASG는
# 사람 개입 없이 인스턴스를 띄우므로 scp가 성립하지 않는다. S3 경유가 필연이다.
#
# 기존 미디어 버킷(terraform/s3.tf)을 재사용하지 않는 이유 세 가지:
#  1) 그 버킷은 CloudFront OAC의 오리진이다. 배포 정책에 따라 JAR이 CDN 경유로 공개될 수 있다.
#  2) 앱용 IAM 유저가 그 버킷에 PutObject/DeleteObject 권한을 갖고 있다. 앱이 자기 배포
#     아티팩트를 지울 수 있는 구조가 되는데, 권한 경계로서 바람직하지 않다.
#  3) 수명 정책이 반대다 — 미디어는 사용자 데이터, 아티팩트는 교체되는 빌드 산출물이다.
resource "aws_s3_bucket" "artifacts" {
  bucket        = var.artifact_bucket_name
  force_destroy = var.artifact_bucket_force_destroy

  tags = {
    Name = "${var.name_prefix}-artifacts"
  }
}

# EC2는 IAM 역할로 GetObject를 호출하므로 버킷이 private이어도 동작에 지장이 없다.
# 오히려 배포 JAR이 공개되면 안 되므로 4개 항목을 모두 차단한다.
resource "aws_s3_bucket_public_access_block" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# 미디어 버킷과 동일하게 SSE-S3(AES256). KMS를 쓰지 않는 이유도 같다 — 이 규모에서 키 관리
# 비용/복잡도를 추가할 근거가 없다.
resource "aws_s3_bucket_server_side_encryption_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 미디어 버킷(terraform/s3.tf)은 버저닝을 끄기로 결정했는데 여기서는 켠다 — 판단이 반대인
# 이유를 남긴다.
#  - 미디어: 사용자의 삭제 요청이 "진짜 삭제"여야 하고, 키가 UUID 기반이라 덮어쓰기 충돌이
#    사실상 없다. 버저닝은 비용만 쌓는다.
#  - 아티팩트: 롤백이 존재 이유다. 잘못된 빌드를 올렸을 때 직전 객체로 되돌릴 수 있어야 한다.
#
# 다만 키 자체를 커밋 SHA로 잡으므로(app/<sha>.jar) 버전 관리의 1차 수단은 키 규약이고,
# 버저닝은 같은 키를 실수로 덮어썼을 때의 2차 안전망이다.
resource "aws_s3_bucket_versioning" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id

  versioning_configuration {
    status = "Enabled"
  }
}
