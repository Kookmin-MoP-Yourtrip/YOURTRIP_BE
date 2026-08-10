output "app_public_ip" {
  description = "App EC2 공인 IP — 로컬 prometheus.yml 스크레이프 타겟, Swagger 접속, k6 BASE_URL에 사용"
  value       = aws_instance.app.public_ip
}

output "app_instance_id" {
  description = "App EC2 인스턴스 ID — SSM Session Manager 포트포워딩(RDS 시딩용, README.md 실행 순서 5번)의 --target 값"
  value       = aws_instance.app.id
}

output "app_ssh_command" {
  description = "App EC2 SSH 접속 명령 (<SSH 개인키 경로>를 실제 경로로 바꿔서 사용)"
  value       = "ssh -i <SSH 개인키 경로> ec2-user@${aws_instance.app.public_ip}"
}

output "k6_public_ip" {
  description = "k6 EC2 공인 IP"
  value       = aws_instance.k6.public_ip
}

output "k6_ssh_command" {
  description = "k6 EC2 SSH 접속 명령 (<SSH 개인키 경로>를 실제 경로로 바꿔서 사용)"
  value       = "ssh -i <SSH 개인키 경로> ec2-user@${aws_instance.k6.public_ip}"
}

output "rds_endpoint" {
  description = "psql -h <이 값> -U <rds_username> -d <rds_db_name>로 seed-benchmark.sql 실행 시 사용"
  value       = aws_db_instance.this.address
}

output "redis_endpoint" {
  description = "ElastiCache 엔드포인트. App EC2의 .env REDIS_HOST에 자동으로 주입되므로 참고/디버깅용."
  value       = aws_elasticache_cluster.this.cache_nodes[0].address
}
