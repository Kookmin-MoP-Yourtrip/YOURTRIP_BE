resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name_prefix}-redis-subnet-group"
  subnet_ids = [aws_subnet.primary.id, aws_subnet.secondary.id]
}

# maxmemory-policy만 오버라이드 — 로컬 docker-compose.yml의
# `redis-server --maxmemory-policy allkeys-lru`와 동일한 축출 정책을 관리형에서도 유지한다
# (순수 캐시라 원본은 항상 DB에 있음, CACHING-ROADMAP.md 설계 원칙 참고).
# maxmemory 자체는 오버라이드하지 않는다 — ElastiCache는 노드 타입별 예약 메모리를
# 제외한 나머지를 자동으로 maxmemory로 산정해주므로 수동 설정이 오히려 위험하다.
resource "aws_elasticache_parameter_group" "this" {
  name   = "${var.name_prefix}-redis7-params"
  family = "redis7"

  parameter {
    name  = "maxmemory-policy"
    value = "allkeys-lru"
  }
}

resource "aws_elasticache_cluster" "this" {
  cluster_id           = "${var.name_prefix}-redis"
  engine               = "redis"
  engine_version       = "7.1"
  node_type            = var.elasticache_node_type
  num_cache_nodes      = 1
  port                 = 6379
  parameter_group_name = aws_elasticache_parameter_group.this.name
  subnet_group_name    = aws_elasticache_subnet_group.this.name
  security_group_ids   = [aws_security_group.elasticache.id]

  # 순수 캐시(원본은 항상 DB)이고 이번 인프라는 측정 후 즉시 철거하는 일회성
  # 테스트라 스냅샷 자체가 불필요하다 — fork 기반 메모리 스파이크 리스크를
  # 원천 차단하는 효과도 있다(자체 호스팅 Redis였다면 --save "" 에 해당).
  snapshot_retention_limit = 0

  apply_immediately = true

  tags = {
    Name = "${var.name_prefix}-redis"
  }
}
