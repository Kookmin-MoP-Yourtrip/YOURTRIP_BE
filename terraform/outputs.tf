output "s3_bucket_name" {
  description = ".env의 S3_BUCKET에 채워 넣을 값"
  value       = aws_s3_bucket.media.bucket
}

output "s3_bucket_arn" {
  description = "생성된 버킷의 ARN (참고/디버깅용)"
  value       = aws_s3_bucket.media.arn
}

output "iam_user_access_key_id" {
  description = ".env의 AWS_ACCESS_KEY에 채워 넣을 값"
  value       = aws_iam_access_key.app.id
}

output "iam_user_secret_access_key" {
  description = ".env의 AWS_SECRET_KEY에 채워 넣을 값. 평문 시크릿이므로 sensitive 처리."
  value       = aws_iam_access_key.app.secret
  sensitive   = true
}

output "cloudfront_domain_name" {
  description = ".env의 CLOUDFRONT_DOMAIN에 채워 넣을 값"
  value       = aws_cloudfront_distribution.media.domain_name
}

output "cloudfront_distribution_id" {
  description = ".env의 CLOUDFRONT_DISTRIBUTION_ID에 채워 넣을 값(invalidation 호출 대상)"
  value       = aws_cloudfront_distribution.media.id
}

output "cloudfront_key_pair_id" {
  description = ".env의 CLOUDFRONT_KEY_PAIR_ID에 채워 넣을 값 — Signed URL 서명 시 이 ID와 개인키 파일이 짝을 이뤄야 한다"
  value       = aws_cloudfront_public_key.signer.id
}
