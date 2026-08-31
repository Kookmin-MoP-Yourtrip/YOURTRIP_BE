# S3Service.getPresignedUrl()을 대체하는 CloudFront 배포. 하나의 배포/버킷을 공유하되
# path pattern으로 공개/비공개를 나눈다:
#   - default(그 외 전체) → 서명 없이 서빙 (uploadcourse/feed/프로필 등 기존 공개 콘텐츠)
#   - "private/*"         → 서명(trusted key group) 필수 (mycourse 장소 이미지 전용)
# 버킷 자체는 여전히 private이며(aws_s3_bucket_public_access_block, s3.tf), CloudFront는
# OAC(Origin Access Control)를 통해서만 접근한다 — "CloudFront가 S3를 읽을 권한이 있는가"와
# "뷰어 요청이 서명되어야 하는가"는 서로 다른 두 메커니즘이다(전자는 아래 OAC+버킷 정책,
# 후자는 트러스트 키 그룹).

resource "aws_cloudfront_origin_access_control" "media" {
  name                              = "${var.bucket_name}-oac"
  description                       = "CloudFront가 media 버킷을 읽기 위한 Origin Access Control"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# mycourse 비공개 이미지의 Signed URL 검증용 키 그룹. 개인키는 애플리케이션(.env의
# CLOUDFRONT_PRIVATE_KEY_PATH)에만 두고 여기서는 공개키만 등록한다 — terraform state에
# 개인키가 절대 들어가지 않는다(README.md의 키페어 생성 절차 참고).
#
# name 대신 name_prefix + create_before_destroy를 쓰는 이유: encoded_key가 바뀌면(예: RSA→ECDSA
# 키 로테이션) 이 리소스는 무조건 교체(destroy 후 create)돼야 하는데, 고정된 name을 그대로 쓰면
# 새 키를 만들기 전에 기존 키부터 지워야 해서 "아직 key group이 참조 중"이라는 CloudFront API
# 오류(PublicKeyInUse)가 난다 — 실제로 겪은 문제다. name_prefix면 새 키가 다른 이름으로 먼저
# 생성되고, 아래 aws_cloudfront_key_group.signer가 새 키를 가리키도록 갱신된 뒤에야 기존 키가
# 삭제되어 무중단으로 로테이션된다.
resource "aws_cloudfront_public_key" "signer" {
  name_prefix = "${var.bucket_name}-signer-key-"

  # 줄바꿈을 LF로 정규화한 뒤 넘긴다. Windows에서 만들어지거나 편집된 .pem은 CRLF가 되는데,
  # state에는 최초 apply 당시의 LF 값이 들어 있어 키 내용이 같아도 encoded_key가 달라 보인다.
  # 그러면 plan이 "forces replacement"를 걸고, 그대로 apply하면 서명 키가 교체돼 이미 발급된
  # Signed URL이 전부 무효화된다. 실제로 이 워킹트리의 .pem이 182바이트(CRLF 4개)여서
  # state의 178바이트와 어긋나 있었다.
  #
  # .pem 파일 자체를 LF로 고치지 않고 여기서 정규화하는 이유: .pem은 .gitignore 대상이라
  # 파일 수정은 그 워킹트리에만 남고 커밋으로 전파되지 않는다. worktree마다 사본이 따로 있어
  # 한 곳을 고쳐도 나머지에서 같은 plan이 다시 뜨고, 키를 새로 만들면 또 재발한다. 코드에서
  # 정규화하면 파일이 어떤 줄바꿈이든 결과가 같아진다.
  encoded_key = replace(file(var.cloudfront_public_key_path), "\r\n", "\n")
  comment     = "mycourse 비공개 장소 이미지 Signed URL 검증용 공개키"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_cloudfront_key_group" "signer" {
  name    = "${var.bucket_name}-signer-key-group"
  items   = [aws_cloudfront_public_key.signer.id]
  comment = "\"private/*\" path pattern 서명 검증에 쓰이는 트러스트 키 그룹"
}

# AWS 관리형 캐시 정책. Query Strings/Headers/Cookies를 캐시 키에 포함하지 않으면서도
# 오리진의 Cache-Control(max-age)을 Min(1초)~Max(1년) 범위 내에서 그대로 존중한다.
# 이 두 특성 덕분에:
#   - 공개/비공개 behavior 모두 이 정책 하나로 충분하다(실제 TTL 차이는 S3Service가
#     업로드 시점에 붙이는 Cache-Control 값으로만 결정됨 — 공개 6개월/비공개 1주일).
#   - mycourse처럼 매 요청 서명이 달라지는 URL도, 쿼리스트링이 캐시 키에서 빠지므로
#     같은 key라면 엣지 캐시를 그대로 재사용한다(서명 검증 자체는 캐시 여부와 무관하게
#     트러스트 키 그룹이 매 요청 수행).
data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

resource "aws_cloudfront_distribution" "media" {
  enabled = true
  comment = "yourtrip 이미지 배포 — 공개 콘텐츠는 무서명, private/*는 서명 필수"

  origin {
    domain_name              = aws_s3_bucket.media.bucket_regional_domain_name
    origin_id                = "s3-media"
    origin_access_control_id = aws_cloudfront_origin_access_control.media.id
  }

  # 공개 콘텐츠(uploadcourse 썸네일/장소이미지, feed 미디어, 프로필 이미지, default-*.png
  # 같은 하드코딩 기본 이미지 등 "private/"로 시작하지 않는 모든 key) — 서명 불필요.
  default_cache_behavior {
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    target_origin_id       = "s3-media"
    viewer_protocol_policy = "redirect-to-https"
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized.id
    compress               = true
  }

  # mycourse 장소 이미지 전용(S3Service.uploadPrivateFile()이 "private/{courseId}/{uuid}.ext"
  # 형식으로 저장). 서명 없는 요청/만료된 서명은 오리진(S3)에 묻지도 않고 엣지에서 403.
  # 경로 세그먼트가 코스 단위로 한 단계 깊어져도 이 path_pattern("private/*")에 그대로
  # 매칭되므로 behavior 수정은 필요 없다(실배포 PoC로 확인 — stage1/design-and-poc.md).
  ordered_cache_behavior {
    path_pattern           = "private/*"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    target_origin_id       = "s3-media"
    viewer_protocol_policy = "https-only"
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized.id
    compress               = true
    trusted_key_groups     = [aws_cloudfront_key_group.signer.id]
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  # 커스텀 도메인/ACM 인증서는 이번 범위 밖 — 기본 *.cloudfront.net 도메인을 그대로 쓴다.
  viewer_certificate {
    cloudfront_default_certificate = true
  }

  price_class = var.cloudfront_price_class
}

# OAC가 CloudFront 서비스 자체(cloudfront.amazonaws.com)에게 이 버킷을 읽을 권한을 주되,
# 반드시 "이 배포"에서 온 요청으로 한정한다(SourceArn 조건) — 다른 계정/배포가 같은 방식으로
# 버킷을 읽어가는 것을 막는다. aws_s3_bucket_public_access_block(s3.tf)의 전체 공개 차단과는
# 충돌하지 않는다 — 이건 "public"이 아니라 특정 서비스 principal 전용 허용이기 때문이다.
data "aws_iam_policy_document" "media_cloudfront_access" {
  statement {
    sid       = "AllowCloudFrontServicePrincipalReadOnly"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.media.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.media.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "media_cloudfront_access" {
  bucket = aws_s3_bucket.media.id
  policy = data.aws_iam_policy_document.media_cloudfront_access.json
}
