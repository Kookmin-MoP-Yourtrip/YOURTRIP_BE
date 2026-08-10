resource "aws_db_subnet_group" "this" {
  name       = "${var.name_prefix}-rds-subnet-group"
  subnet_ids = [aws_subnet.primary.id, aws_subnet.secondary.id]

  tags = {
    Name = "${var.name_prefix}-rds-subnet-group"
  }
}

resource "aws_db_instance" "this" {
  identifier     = "${var.name_prefix}-postgres"
  engine         = "postgres"
  engine_version = var.rds_engine_version
  instance_class = var.rds_instance_class

  allocated_storage = 20
  storage_type      = "gp2"

  db_name  = var.rds_db_name
  username = var.rds_username
  password = var.rds_password

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  availability_zone      = var.availability_zone_primary

  multi_az            = false
  publicly_accessible = false
  storage_encrypted   = false

  # 로컬 벤치마크와 동일하게 매 측정 전 seed-benchmark.sql로 재시딩하는 일회성
  # 테스트 DB라 자동 백업/최종 스냅샷이 불필요하다 — 관리 부담과 destroy 소요
  # 시간만 늘어난다(운영 DB였다면 이 설정들은 절대 이렇게 두면 안 된다).
  backup_retention_period = 0
  skip_final_snapshot     = true
  deletion_protection     = false
  apply_immediately       = true

  tags = {
    Name = "${var.name_prefix}-postgres"
  }
}
