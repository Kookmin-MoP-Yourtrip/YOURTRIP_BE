# 애플리케이션(S3Config.java)이 AwsBasicCredentials로 사용할 프로그래매틱 전용 IAM 사용자.
# 콘솔 로그인 프로필(aws_iam_user_login_profile)은 만들지 않는다 — API 호출에만 쓰이는
# 계정에 콘솔 비밀번호를 발급하는 것은 불필요한 공격 표면이다.
resource "aws_iam_user" "app" {
  name = var.iam_user_name
  path = "/service-accounts/"
}

# S3Config.java가 정적 자격증명(access key/secret key) 방식을 전제하고 있으므로
# 그에 맞춰 access key를 발급한다.
resource "aws_iam_access_key" "app" {
  user = aws_iam_user.app.name
}

# S3Service.java가 실제로 호출하는 SDK 메서드는 putObject / deleteObject /
# (presigner를 통한) getObject 세 가지뿐이다. 이 세 개만 허용한다.
data "aws_iam_policy_document" "app_s3_access" {
  statement {
    sid    = "AllowObjectReadWriteDelete"
    effect = "Allow"
    actions = [
      "s3:PutObject",
      "s3:GetObject",
      "s3:DeleteObject",
    ]
    resources = ["${aws_s3_bucket.media.arn}/*"]
  }

  # 코드 전체에 ListObjectsV2 등 리스트 계열 API 호출이 없어 기본은 비활성.
  # 관리자용 파일 목록 조회 기능이 추가되면 var.grant_list_bucket = true로 전환한다.
  # ListBucket은 객체 ARN이 아니라 버킷 ARN을 대상으로 해야 한다 — 이 둘을 혼용하면
  # ListBucket이 항상 AccessDenied가 나는 흔한 실수이므로 별도 statement로 분리한다.
  dynamic "statement" {
    for_each = var.grant_list_bucket ? [1] : []
    content {
      sid       = "AllowListBucket"
      effect    = "Allow"
      actions   = ["s3:ListBucket"]
      resources = [aws_s3_bucket.media.arn]
    }
  }
}

resource "aws_iam_policy" "app_s3_access" {
  name   = "${var.iam_user_name}-s3-access"
  policy = data.aws_iam_policy_document.app_s3_access.json
}

resource "aws_iam_user_policy_attachment" "app_s3_access" {
  user       = aws_iam_user.app.name
  policy_arn = aws_iam_policy.app_s3_access.arn
}

# S3Service.deleteFile()이 객체 삭제 후 CloudFront 캐시도 함께 지우기 위해
# CloudFrontService.invalidate()를 호출한다(cloudfront.tf 참고). 이 권한은
# 해당 배포 하나로만 한정한다 — 다른 배포를 무효화할 수 없도록.
data "aws_iam_policy_document" "app_cloudfront_invalidation" {
  statement {
    sid       = "AllowCreateInvalidation"
    effect    = "Allow"
    actions   = ["cloudfront:CreateInvalidation"]
    resources = [aws_cloudfront_distribution.media.arn]
  }
}

resource "aws_iam_policy" "app_cloudfront_invalidation" {
  name   = "${var.iam_user_name}-cloudfront-invalidation"
  policy = data.aws_iam_policy_document.app_cloudfront_invalidation.json
}

resource "aws_iam_user_policy_attachment" "app_cloudfront_invalidation" {
  user       = aws_iam_user.app.name
  policy_arn = aws_iam_policy.app_cloudfront_invalidation.arn
}
