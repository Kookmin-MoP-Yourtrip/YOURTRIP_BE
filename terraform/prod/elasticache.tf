resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name_prefix}-redis-subnet-group"
  subnet_ids = [aws_subnet.primary.id, aws_subnet.secondary.id]
}

# maxmemory-policy만 오버라이드한다 — 로컬 docker-compose.yml의
# `redis-server --maxmemory-policy allkeys-lru`와 동일한 축출 정책을 관리형에서도 유지한다
# (순수 캐시라 원본은 항상 DB에 있다, docs/tasks/redis-caching/README.md).
# maxmemory 자체는 건드리지 않는다 — ElastiCache가 노드 타입별 예약 메모리를 제외하고
# 자동 산정하므로 수동 설정이 오히려 위험하다.
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

  # ⚠️ 이 한 줄이 이 파일에서 가장 중요하다.
  #
  # 서브넷 그룹이 primary(2a)·secondary(2c)를 모두 포함하는데 AZ를 지정하지 않으면 AWS가
  # 임의로 고른다. 부하테스트 환경에서 실제로 2c에 떨어져 App EC2(2a)와의 모든 Redis 명령이
  # AZ를 횡단했고, Redis 명령 지연의 바닥값이 1.2ms로 굳었다 — 같은 AZ면 통상 0.2~0.4ms이고
  # /popular 히트 1건은 명령 2회라 요청당 약 2.4ms가 순수 왕복 비용이었다
  # (docs/tasks/cache-effect-measurement/redis-io-bottleneck.md "바닥값 1.2ms" 절, 커밋 7cbef86).
  #
  # 인자명 주의: AWS API의 PreferredAvailabilityZone에 대응하는 Terraform 인자는
  # availability_zone이다. preferred_availability_zones(복수)와 az_mode는 Memcached 전용이라
  # Redis에는 쓸 수 없다. 변경 시 클러스터가 재생성된다(ForceNew).
  availability_zone = var.availability_zone_primary

  # 순수 캐시라 원본은 항상 DB에 있고, 이 환경은 데모·측정이 끝나면 철거한다. 스냅샷은
  # 불필요하며, fork 기반 메모리 스파이크 리스크를 원천 차단하는 효과도 있다.
  snapshot_retention_limit = 0

  apply_immediately = true

  # transit_encryption_enabled를 켜지 않는다 — 앱이 spring.data.redis.ssl을 설정하지 않아
  # 앱 변경이 선행돼야 하고, 실제 경계는 SG 참조 규칙이다. 다만 퍼블릭 서브넷을 쓰므로
  # 실질 경계가 SG 하나뿐이라는 점은 설계 문서에 한계로 적어뒀다.

  tags = {
    Name = "${var.name_prefix}-redis"
  }
}
