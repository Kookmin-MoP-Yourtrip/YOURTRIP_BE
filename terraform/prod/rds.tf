resource "aws_db_subnet_group" "this" {
  name       = "${var.name_prefix}-rds-subnet-group"
  subnet_ids = [aws_subnet.primary.id, aws_subnet.secondary.id]

  tags = {
    Name = "${var.name_prefix}-rds-subnet-group"
  }
}

# 마스터 비밀번호는 SSM Parameter Store가 유일한 원본이다. tfvars에 두면 같은 값을 두 곳에서
# 관리하게 되고, 앱도 어차피 SSM에서 받아 쓰므로 여기서도 같은 곳을 읽는다.
#
# tfstate에는 여전히 평문으로 남는다 — RDS 마스터 비밀번호의 구조적 한계이며 tfstate 자체를
# 비밀로 취급하는 것으로 대응한다(terraform/README.md의 트레이드오프 절과 같은 입장).
#
# manage_master_user_password(Secrets Manager 위임)를 쓰지 않는 이유: destroy 시 시크릿이
# 7일 복구 대기 상태로 들어가 같은 이름으로 재생성이 7일간 실패한다. 온디맨드로 apply/destroy를
# 반복하는 이 환경에서는 두 번째 apply부터 막힌다.
# 경로에 /env/가 끼는 이유: 앱의 .env로 들어갈 값들과 파일로 떨어져야 하는 PEM을 분리했다
# (variables.tf의 ssm_parameter_path 설명 참고). user-data도 이 경로만 일괄 조회한다.
data "aws_ssm_parameter" "db_password" {
  name            = "${var.ssm_parameter_path}/env/DB_PASSWORD"
  with_decryption = true
}

resource "aws_db_instance" "this" {
  identifier     = "${var.name_prefix}-postgres"
  engine         = "postgres"
  engine_version = var.rds_engine_version
  instance_class = var.rds_instance_class

  allocated_storage = var.rds_allocated_storage
  storage_type      = "gp2"

  db_name  = var.rds_db_name
  username = var.rds_username
  password = data.aws_ssm_parameter.db_password.value

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  # ElastiCache·App EC2와 같은 AZ에 고정한다. 데이터 계층이 앱과 다른 AZ에 떨어지면
  # 모든 쿼리가 AZ를 횡단한다.
  availability_zone = var.availability_zone_primary

  # 앱 계층만 이중화되고 DB는 페일오버가 없다. Multi-AZ는 비용이 두 배가 되는데, 데모·측정
  # 목적의 환경에서 그 비용을 정당화할 근거가 없다. 이 한계는 설계 문서에 명시돼 있다.
  multi_az = false

  # 퍼블릭 서브넷에 있지만 엔드포인트는 사설 IP로만 resolve된다. 외부에서 붙어야 할 때는
  # App EC2를 경유하는 SSM 포트포워딩을 쓴다.
  publicly_accessible = false

  # loadtest는 false였다. 무료이고 성능 영향도 없어 켜지 않을 이유가 없는데,
  # ⚠️ 생성 후에는 바꿀 수 없다(스냅샷을 뜨고 복원해야 한다). 최초 apply 전에 결정해야 하는 값이다.
  storage_encrypted = true

  # 0이면 자동 백업이 아예 없어 PITR이 불가능하다. 매일 destroy되는 DB에 7일 보존을 거는 것은
  # 실속 없는 형식이라 최솟값 1로 둔다 — "복구 가능성이 0은 아니다" 수준이지 DR 태세가 아니다.
  backup_retention_period = 1

  # 온디맨드 모델과의 충돌을 해소하는 부분이다. 기본은 스냅샷 없이 빠르게 destroy하고,
  # 데이터를 남겨야 하는 날만 destroy 시점에 접미사를 넘긴다:
  #   terraform destroy -var 'rds_final_snapshot_suffix=2026-08-25-demo'
  #
  # timestamp()를 쓰지 않는 이유: 리소스 인자에 넣으면 매 plan마다 diff가 뜨고,
  # ignore_changes로 막으면 값이 최초 apply 시점에 고정돼 두 번째 destroy에서 이름이 충돌한다.
  skip_final_snapshot       = var.rds_final_snapshot_suffix == ""
  final_snapshot_identifier = var.rds_final_snapshot_suffix == "" ? null : "${var.name_prefix}-final-${var.rds_final_snapshot_suffix}"

  # true면 terraform destroy가 막혀 "보호를 끄는 apply" → "destroy" 두 단계가 된다.
  # 온디맨드 모델에서는 매번 그 두 단계를 밟게 되므로 끈다(변수로 노출해 의도를 드러낸다).
  deletion_protection = var.rds_deletion_protection

  # 인스턴스가 다음 유지보수 창까지 살아 있지 않다. false면 변경이 조용히 영원히 반영되지 않는다.
  apply_immediately = true

  # 무료 구간(7일)이고, 이 저장소는 이미 측정으로 의사결정을 하는 방식을 쓴다.
  # 느린 쿼리·대기 이벤트를 사후에 확인할 수 있으면 실측 문서의 근거가 늘어난다.
  performance_insights_enabled          = true
  performance_insights_retention_period = 7

  tags = {
    Name = "${var.name_prefix}-postgres"
  }
}
