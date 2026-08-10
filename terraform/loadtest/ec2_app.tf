data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }

  filter {
    name   = "architecture"
    values = ["x86_64"]
  }
}

resource "aws_instance" "app" {
  ami                         = data.aws_ami.al2023.id
  instance_type               = var.app_instance_type
  subnet_id                   = aws_subnet.primary.id
  vpc_security_group_ids      = [aws_security_group.app.id]
  key_name                    = aws_key_pair.ssh.key_name
  iam_instance_profile        = aws_iam_instance_profile.app_ec2.name
  monitoring                  = var.enable_detailed_monitoring
  associate_public_ip_address = true

  # user_data는 기본적으로 AWS 인스턴스 속성만 갱신될 뿐 이미 부팅된 인스턴스에서
  # cloud-init을 재실행하지 않는다(실측으로 확인됨 — 껐다 켜도 재실행 안 됨) — 이 옵션이
  # 있어야 user_data가 바뀔 때 Terraform이 인스턴스를 자동으로 재생성해 새 스크립트가
  # 실제로 실행되게 한다.
  user_data_replace_on_change = true

  # 이 인스턴스는 빌드를 하지 않는다(JAR는 로컬 빌드 후 scp로 전달 — 이유는
  # templates/app-user-data.sh.tpl 상단 주석 참고) — 기본 루트 볼륨(8GB)이면
  # OS+JRE+JAR+로그로 충분하지만, CloudWatch Agent 등 여유를 두기 위해 10GB로 설정.
  root_block_device {
    volume_size = 10
    volume_type = "gp3"
  }

  # RDS/ElastiCache 엔드포인트가 필요하므로 두 리소스가 먼저 생성돼야 한다.
  user_data = templatefile("${path.module}/templates/app-user-data.sh.tpl", {
    db_host                    = aws_db_instance.this.address
    db_name                    = var.rds_db_name
    db_username                = var.rds_username
    db_password                = var.rds_password
    jwt_secret                 = var.jwt_secret
    mail_email                 = var.mail_email
    mail_password              = var.mail_password
    kakao_api_key              = var.kakao_api_key
    s3_bucket                  = var.s3_bucket
    aws_access_key             = var.aws_access_key
    aws_secret_key             = var.aws_secret_key
    cloudfront_domain          = var.cloudfront_domain
    cloudfront_key_pair_id     = var.cloudfront_key_pair_id
    cloudfront_distribution_id = var.cloudfront_distribution_id
    gemini_api_key             = var.gemini_api_key
    redis_host                 = aws_elasticache_cluster.this.cache_nodes[0].address
  })

  tags = {
    Name = "${var.name_prefix}-app"
  }
}
