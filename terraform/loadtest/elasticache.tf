resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name_prefix}-redis-subnet-group"
  subnet_ids = [aws_subnet.primary.id, aws_subnet.secondary.id]
}

# maxmemory-policy만 오버라이드 — 로컬 docker-compose.yml의
# `redis-server --maxmemory-policy allkeys-lru`와 동일한 축출 정책을 관리형에서도 유지한다
# (순수 캐시라 원본은 항상 DB에 있음, docs/tasks/redis-caching/README.md 설계 원칙 참고).
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

  # 서브넷 그룹이 primary(2a)·secondary(2c)를 모두 포함하는데 AZ를 지정하지 않으면 AWS가
  # 임의로 고른다 — 실제로 2c에 떨어져 App EC2(2a)와의 모든 Redis 명령이 AZ를 횡단했다.
  # 그 결과 Redis 명령 지연의 바닥값이 1.2ms로 굳었는데, 같은 AZ면 통상 0.2~0.4ms이고
  # /popular 히트 1건은 명령 2회라 요청당 약 2.4ms가 순수 왕복 비용이었다
  # (docs/tasks/cache-effect-measurement/redis-io-bottleneck.md "바닥값 1.2ms" 절).
  #
  # vpc.tf 상단 주석과 variables.tf의 availability_zone_primary 설명은 이미 "EC2/RDS/
  # ElastiCache를 전부 이 AZ로 고정한다"고 적고 있었다 — 설계 의도는 처음부터 그랬고
  # 이 리소스에만 구현이 빠져 있었다. EC2·RDS와 같은 변수를 재사용해 셋이 항상 함께 움직이게 한다.
  #
  # 인자명 주의: AWS API의 PreferredAvailabilityZone에 대응하는 Terraform 인자는
  # availability_zone이다. preferred_availability_zones(복수)는 Memcached 전용이라 Redis에
  # 쓸 수 없고, az_mode도 Memcached 전용이다. 변경 시 클러스터가 재생성된다(ForceNew).
  availability_zone = var.availability_zone_primary

  # 순수 캐시(원본은 항상 DB)이고 이번 인프라는 측정 후 즉시 철거하는 일회성
  # 테스트라 스냅샷 자체가 불필요하다 — fork 기반 메모리 스파이크 리스크를
  # 원천 차단하는 효과도 있다(자체 호스팅 Redis였다면 --save "" 에 해당).
  snapshot_retention_limit = 0

  apply_immediately = true

  tags = {
    Name = "${var.name_prefix}-redis"
  }
}
