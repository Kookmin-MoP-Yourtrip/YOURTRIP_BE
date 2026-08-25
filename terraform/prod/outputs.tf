output "vpc_id" {
  description = "생성된 운영 VPC (참고/디버깅용)"
  value       = aws_vpc.this.id
}

output "rds_endpoint" {
  description = "RDS 엔드포인트(호스트). SSM 포트포워딩 대상이자 앱 DB_URL의 호스트 부분이다. publicly_accessible=false이므로 사설 IP로만 resolve된다."
  value       = aws_db_instance.this.address
}

output "redis_endpoint" {
  description = "ElastiCache 엔드포인트(호스트). 앱 REDIS_HOST에 들어가는 값이다."
  value       = aws_elasticache_cluster.this.cache_nodes[0].address
}

output "app_security_group_id" {
  description = "App EC2 보안그룹 (참고/디버깅용)"
  value       = aws_security_group.app.id
}

output "alb_security_group_id" {
  description = "ALB 보안그룹 (참고/디버깅용)"
  value       = aws_security_group.alb.id
}

output "app_instance_profile_name" {
  description = "Launch Template이 사용할 인스턴스 프로파일 이름 (참고/디버깅용)"
  value       = aws_iam_instance_profile.app_ec2.name
}

output "alb_dns_name" {
  description = "ALB의 기본 DNS명. DNS 전환 전에 curl --resolve로 실도메인처럼 검증할 때 이 이름을 IP로 풀어 쓴다."
  value       = aws_lb.this.dns_name
}

output "alb_zone_id" {
  description = "Route53 alias 레코드가 필요로 하는 ALB의 호스티드존 ID"
  value       = aws_lb.this.zone_id
}

output "alb_arn_suffix" {
  description = "CloudWatch 지표 조회(HTTPCode_ELB_502_Count 등)에 쓰는 LoadBalancer 차원 값"
  value       = aws_lb.this.arn_suffix
}

output "target_group_arn" {
  description = "타깃 헬스 확인(aws elbv2 describe-target-health)에 쓰는 ARN"
  value       = aws_lb_target_group.app.arn
}

output "asg_name" {
  description = "스케일링 활동 조회(aws autoscaling describe-scaling-activities)와 인스턴스 ID 조회에 쓰는 이름"
  value       = aws_autoscaling_group.app.name
}

output "app_ami_id" {
  description = "이번 apply가 실제로 사용한 AMI. 환경을 고정해야 하는 측정·데모에서는 이 값을 tfvars의 app_ami_id에 박아 재현성을 확보한다."
  value       = local.app_ami_id
}
