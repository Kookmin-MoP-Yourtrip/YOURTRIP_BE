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
