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

  # 기본 루트 볼륨(8GB)은 Gradle 의존성 캐시+빌드 산출물까지 감안하면 빠듯할 수 있어
  # 여유를 둔다. gp3 15GB는 프리티어(월 30GB gp2/gp3 무료) 안에 충분히 들어온다.
  root_block_device {
    volume_size = 15
    volume_type = "gp3"
  }

  # RDS/ElastiCache 엔드포인트가 필요하므로 두 리소스가 먼저 생성돼야 한다.
  user_data = templatefile("${path.module}/templates/app-user-data.sh.tpl", {
    app_repo_url               = var.app_repo_url
    app_git_ref                = var.app_git_ref
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
