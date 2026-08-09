resource "aws_security_group" "app" {
  name        = "${var.name_prefix}-sg-app"
  description = "App EC2 - Spring Boot API + Actuator"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-sg-app"
  }
}

resource "aws_security_group" "k6" {
  name        = "${var.name_prefix}-sg-k6"
  description = "Dedicated EC2 for k6 load generator"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-sg-k6"
  }
}

resource "aws_security_group" "rds" {
  name        = "${var.name_prefix}-sg-rds"
  description = "RDS PostgreSQL"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-sg-rds"
  }
}

resource "aws_security_group" "elasticache" {
  name        = "${var.name_prefix}-sg-elasticache"
  description = "ElastiCache Redis"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-sg-elasticache"
  }
}

# ---------------- app ----------------

resource "aws_security_group_rule" "app_ingress_api_from_k6" {
  type                     = "ingress"
  security_group_id        = aws_security_group.app.id
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.k6.id
  description              = "Load traffic from k6 EC2"
}

resource "aws_security_group_rule" "app_ingress_api_from_dev" {
  type              = "ingress"
  security_group_id = aws_security_group.app.id
  from_port         = 8080
  to_port           = 8080
  protocol          = "tcp"
  cidr_blocks       = [var.my_ip_cidr]
  description       = "Remote Prometheus scrape (/actuator/prometheus) + manual Swagger check from dev machine"
}

resource "aws_security_group_rule" "app_ingress_ssh_from_dev" {
  type              = "ingress"
  security_group_id = aws_security_group.app.id
  from_port         = 22
  to_port           = 22
  protocol          = "tcp"
  cidr_blocks       = [var.my_ip_cidr]
  description       = "SSH for troubleshooting and CloudFront private key delivery"
}

resource "aws_security_group_rule" "app_egress_all" {
  type              = "egress"
  security_group_id = aws_security_group.app.id
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
}

# ---------------- k6 ----------------

resource "aws_security_group_rule" "k6_ingress_ssh_from_dev" {
  type              = "ingress"
  security_group_id = aws_security_group.k6.id
  from_port         = 22
  to_port           = 22
  protocol          = "tcp"
  cidr_blocks       = [var.my_ip_cidr]
  description       = "SSH to log in and run k6"
}

resource "aws_security_group_rule" "k6_egress_all" {
  type              = "egress"
  security_group_id = aws_security_group.k6.id
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
}

# ---------------- rds ----------------

resource "aws_security_group_rule" "rds_ingress_from_app" {
  type                     = "ingress"
  security_group_id        = aws_security_group.rds.id
  from_port                = 5432
  to_port                  = 5432
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.app.id
  description              = "HikariCP connections from the app"
}

resource "aws_security_group_rule" "rds_ingress_from_dev" {
  count             = var.allow_dev_psql_access ? 1 : 0
  type              = "ingress"
  security_group_id = aws_security_group.rds.id
  from_port         = 5432
  to_port           = 5432
  protocol          = "tcp"
  cidr_blocks       = [var.my_ip_cidr]
  description       = "Direct psql access for EXPLAIN ANALYZE etc. (optional, toggled by allow_dev_psql_access)"
}

resource "aws_security_group_rule" "rds_egress_all" {
  type              = "egress"
  security_group_id = aws_security_group.rds.id
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
}

# ---------------- elasticache ----------------

resource "aws_security_group_rule" "elasticache_ingress_from_app" {
  type                     = "ingress"
  security_group_id        = aws_security_group.elasticache.id
  from_port                = 6379
  to_port                  = 6379
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.app.id
  description              = "Cache lookups from the app"
}

resource "aws_security_group_rule" "elasticache_egress_all" {
  type              = "egress"
  security_group_id = aws_security_group.elasticache.id
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
}
