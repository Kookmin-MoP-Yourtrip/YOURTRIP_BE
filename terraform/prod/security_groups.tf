# 보안그룹은 빈 껍데기(aws_security_group)와 규칙(aws_security_group_rule)을 분리한다 —
# terraform/loadtest/security_groups.tf와 동일한 구조다. 규칙을 별개 리소스로 두면
# `-target`으로 규칙 하나만 골라 apply할 수 있어, 접속이 막혔을 때 전체 plan을 돌리지 않고
# 좁게 고칠 수 있다.
#
# loadtest와 달라진 점:
#  - k6 SG가 없다(부하 생성기는 이 환경에 없다).
#  - ALB SG가 새로 생겼고, app SG의 8080은 개발자 IP가 아니라 ALB SG를 참조한다.
#    이게 "모든 트래픽은 ALB를 통과한다"를 네트워크 수준에서 강제하는 지점이고,
#    /actuator 차단 리스너 규칙이 의미를 갖는 전제이기도 하다.
#
# description이 전부 영문인 이유: AWS가 이 필드를 ASCII 부분집합으로 제한한다
# (^[0-9A-Za-z_ .:/()#,@\[\]+=&;{}!$*-]*$). 한글은 물론 화살표(>)도 거부되므로,
# 설명은 주석으로 쓰고 필드에는 최소한의 영문만 넣는다.

# ============================================================
# ALB — 인터넷에서 직접 트래픽을 받는 유일한 지점
# ============================================================

resource "aws_security_group" "alb" {
  name        = "${var.name_prefix}-alb-sg"
  description = "ALB. Accepts 80/443 from internet, forwards to app 8080."
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-alb-sg"
  }
}

# 80은 서비스용이 아니라 301 redirect 전용이다(리스너에서 처리). 그래도 열어야 하는 이유는
# 브라우저가 http://도메인 으로 먼저 오는 경우를 잡아 https로 보내야 하기 때문이다.
resource "aws_security_group_rule" "alb_ingress_http_from_internet" {
  type              = "ingress"
  security_group_id = aws_security_group.alb.id
  from_port         = 80
  to_port           = 80
  protocol          = "tcp"
  cidr_blocks       = ["0.0.0.0/0"]
  description       = "HTTP (redirect to HTTPS only)"
}

resource "aws_security_group_rule" "alb_ingress_https_from_internet" {
  type              = "ingress"
  security_group_id = aws_security_group.alb.id
  from_port         = 443
  to_port           = 443
  protocol          = "tcp"
  cidr_blocks       = ["0.0.0.0/0"]
  description       = "HTTPS"
}

resource "aws_security_group_rule" "alb_egress_all" {
  type              = "egress"
  security_group_id = aws_security_group.alb.id
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
  description       = "Target health checks and forwarding"
}

# ============================================================
# App EC2 — ALB 뒤에 숨는다
# ============================================================

resource "aws_security_group" "app" {
  name        = "${var.name_prefix}-app-sg"
  description = "App EC2 (ASG). 8080 from ALB only, SSH from developer IP only."
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-app-sg"
  }
}

# CIDR이 아니라 ALB의 SG를 참조한다 — ALB 노드의 사설 IP가 바뀌어도(스케일·교체) 규칙을
# 고칠 필요가 없고, 인터넷에서 8080으로 직접 들어오는 경로가 원천적으로 없어진다.
resource "aws_security_group_rule" "app_ingress_api_from_alb" {
  type                     = "ingress"
  security_group_id        = aws_security_group.app.id
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.alb.id
  description              = "ALB to app"
}

# 디버깅용 우회로. 기본은 만들지 않는다(count = 0) — 이 규칙이 살아 있으면 개발자 IP에서는
# /actuator 차단 리스너 규칙을 그냥 지나칠 수 있어, 차단이 걸려 있다는 착각을 만든다.
resource "aws_security_group_rule" "app_ingress_api_from_dev" {
  count = var.enable_dev_direct_access ? 1 : 0

  type              = "ingress"
  security_group_id = aws_security_group.app.id
  from_port         = 8080
  to_port           = 8080
  protocol          = "tcp"
  cidr_blocks       = [var.my_ip_cidr]
  description       = "Developer IP to app directly (bypasses ALB, debugging only)"
}

# 평상시 접속은 SSM Session Manager를 쓴다(IAM 역할에 AmazonSSMManagedInstanceCore가 붙어
# 있다). 이 규칙은 SSM이 동작하지 않을 때의 탈출구다.
resource "aws_security_group_rule" "app_ingress_ssh_from_dev" {
  type              = "ingress"
  security_group_id = aws_security_group.app.id
  from_port         = 22
  to_port           = 22
  protocol          = "tcp"
  cidr_blocks       = [var.my_ip_cidr]
  description       = "SSH (break-glass)"
}

# 앱이 S3·CloudFront·OpenAI·Kakao·네이버·TourAPI·SMTP로 나가야 하고, 부팅 시 dnf·SSM·S3도 호출한다.
resource "aws_security_group_rule" "app_egress_all" {
  type              = "egress"
  security_group_id = aws_security_group.app.id
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
  description       = "External APIs, package repos, AWS APIs"
}

# ============================================================
# RDS / ElastiCache — 앱에서만 닿는다
# ============================================================

resource "aws_security_group" "rds" {
  name        = "${var.name_prefix}-rds-sg"
  description = "RDS. Accepts 5432 from app SG only."
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-rds-sg"
  }
}

# 퍼블릭 서브넷에 있지만 publicly_accessible=false이고 이 규칙이 유일한 입구다.
# 로컬에서 psql로 붙어야 할 때는 App EC2를 경유하는 SSM 포트포워딩을 쓴다.
resource "aws_security_group_rule" "rds_ingress_from_app" {
  type                     = "ingress"
  security_group_id        = aws_security_group.rds.id
  from_port                = 5432
  to_port                  = 5432
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.app.id
  description              = "App to PostgreSQL"
}

resource "aws_security_group_rule" "rds_egress_all" {
  type              = "egress"
  security_group_id = aws_security_group.rds.id
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
  description       = "Default egress"
}

resource "aws_security_group" "elasticache" {
  name        = "${var.name_prefix}-elasticache-sg"
  description = "ElastiCache. Accepts 6379 from app SG only."
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-elasticache-sg"
  }
}

resource "aws_security_group_rule" "elasticache_ingress_from_app" {
  type                     = "ingress"
  security_group_id        = aws_security_group.elasticache.id
  from_port                = 6379
  to_port                  = 6379
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.app.id
  description              = "App to Redis"
}

resource "aws_security_group_rule" "elasticache_egress_all" {
  type              = "egress"
  security_group_id = aws_security_group.elasticache.id
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
  description       = "Default egress"
}
