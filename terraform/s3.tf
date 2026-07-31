# 애플리케이션(S3Service.java)이 업로드/조회/삭제하는 미디어(프로필 이미지, 코스 장소 사진,
# 피드 미디어, 코스 썸네일)를 저장하는 단일 버킷. 모든 도메인이 이 버킷 하나를 공유하며
# uploads/{yyyy-MM-dd}/{uuid}.{ext} 키로 날짜별 prefix만 구분한다(버킷 분리 불필요).
resource "aws_s3_bucket" "media" {
  bucket = var.bucket_name

  # 방금 만든 버킷을 실수로 destroy했을 때 데이터를 함께 날리지 않도록 기본은 차단한다.
  # 재현/삭제가 필요할 때는 var.bucket_force_destroy로 명시적으로 override한다.
  force_destroy = var.bucket_force_destroy
}

# S3Service.getPresignedUrl()이 GET presigned URL(15분 TTL)만 발급하고, 버킷 정책이나
# 오브젝트 ACL을 public으로 여는 코드가 전혀 없다. 즉 버킷이 private이어도 앱 동작에
# 지장이 없으므로 퍼블릭 접근을 4개 항목 모두 차단한다.
resource "aws_s3_bucket_public_access_block" "media" {
  bucket = aws_s3_bucket.media.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# S3Service.java 93~94행 주석("SSE-S3 기본 암호화 켰으면 생략 가능")이 버킷 레벨
# 기본 암호화를 전제하고 있으므로, 코드 의도와 일치시키기 위해 SSE-S3(AES256)를 켠다.
# SSE-KMS를 쓰지 않는 이유: 코드 주석이 명시한 알고리즘(AES256)과 일치시키기 위함이며,
# 이 규모 프로젝트에서 KMS 키 관리 비용/복잡도를 추가할 근거가 없다.
resource "aws_s3_bucket_server_side_encryption_configuration" "media" {
  bucket = aws_s3_bucket.media.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# AWS 기본값과 동일(Disabled)하지만 의도적으로 명시한다: "고민 없이 방치된 기본값"과
# "검토 후 끄기로 결정한 값"을 코드만 보고 구분하기 위함이다.
# - S3Service.deleteFile()은 사용자의 명시적 삭제 요청에 대응한다. 버저닝을 켜면 delete는
#   delete marker만 추가하고 이전 버전이 스토리지에 남아 "삭제하면 진짜 지워진다"는 앱의
#   기대 동작과 어긋나고 비용만 쌓인다.
# - 업로드 키가 uploads/{date}/{uuid}.{ext}로 UUID 기반이라 동일 키 덮어쓰기 충돌
#   가능성이 사실상 없어, 버저닝이 방어하는 "덮어쓰기 실수" 시나리오 자체가 희박하다.
resource "aws_s3_bucket_versioning" "media" {
  bucket = aws_s3_bucket.media.id

  versioning_configuration {
    status = "Disabled"
  }
}
