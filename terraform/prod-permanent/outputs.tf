output "nameservers" {
  description = "가비아 콘솔의 '네임서버 설정'에 그대로 입력할 4개 값. 이 위임이 끝나야 ACM DNS 검증이 성공한다."
  value       = aws_route53_zone.this.name_servers
}

output "route53_zone_id" {
  description = "terraform/prod/의 terraform.tfvars에 채워 넣을 값(ALB alias 레코드가 들어갈 존)"
  value       = aws_route53_zone.this.zone_id
}

output "acm_certificate_arn" {
  description = "terraform/prod/의 terraform.tfvars에 채워 넣을 값(ALB HTTPS 리스너의 인증서). aws_acm_certificate가 아니라 validation 리소스를 참조해, 검증이 끝나기 전에는 이 출력을 쓸 수 없게 한다."
  value       = aws_acm_certificate_validation.this.certificate_arn
}

output "artifact_bucket_name" {
  description = "terraform/prod/의 terraform.tfvars에 채워 넣을 값. JAR 업로드 대상이기도 하다(aws s3 cp ... s3://<이 값>/app/<sha>.jar)"
  value       = aws_s3_bucket.artifacts.bucket
}

output "artifact_bucket_arn" {
  description = "terraform/prod/의 EC2 IAM 정책이 s3:GetObject를 허용할 대상 ARN (참고/디버깅용)"
  value       = aws_s3_bucket.artifacts.arn
}
