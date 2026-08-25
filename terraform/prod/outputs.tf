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
