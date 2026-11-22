# 앱 앞단의 유일한 진입점. 앱은 8080에서 평문 HTTP로 뜨고(server.ssl.enabled: false),
# TLS는 여기서 끝난다. application.yml의 forward-headers-strategy: framework가 이미
# "앞에 프록시가 있다"를 전제하고 있어 앱 코드 변경 없이 맞물린다.
resource "aws_lb" "this" {
  name               = "${var.name_prefix}-alb"
  load_balancer_type = "application"
  internal           = false
  security_groups    = [aws_security_group.alb.id]

  # ALB는 최소 2개 AZ의 서브넷을 요구한다. 앱 인스턴스는 primary 한 곳에만 배치하지만
  # ALB 노드는 두 AZ에 걸쳐 뜬다 — 그래서 vpc.tf에서 secondary에도 퍼블릭 IP를 켰다.
  subnets = [aws_subnet.primary.id, aws_subnet.secondary.id]

  # 백엔드 keep-alive(65s)보다 짧아야 한다. 더 길면 ALB가 Tomcat이 방금 닫은 연결을
  # 재사용하려다 간헐 502가 난다 — 이 조합은 사전 등록한 판정 기준 P10으로 검증한다.
  idle_timeout = var.alb_idle_timeout

  tags = {
    Name = "${var.name_prefix}-alb"
  }
}

resource "aws_lb_target_group" "app" {
  name     = "${var.name_prefix}-tg"
  port     = 8080
  protocol = "HTTP"
  vpc_id   = aws_vpc.this.id

  # 기본 300초는 온디맨드 모델에서 scale-in과 destroy를 매번 5분씩 늘린다.
  deregistration_delay = var.target_group_deregistration_delay

  health_check {
    enabled = true
    # /actuator/health가 아니라 liveness다 — 이유는 variables.tf의 health_check_path 설명 참고.
    # 이 경로는 ALB 내부에서 타깃으로 직접 가므로, 아래 /actuator 차단 리스너 규칙의 영향을
    # 받지 않는다. 그 공존이 실제로 성립하는지는 판정 기준 P4로 확인한다.
    path = var.health_check_path
    # 트래픽 포트(8080)가 아니라 관리 포트로 검사한다(#216). 8080으로 검사하면 헬스체크가 사용자
    # 요청과 같은 워커 줄에 서서, 과부하 때 일하는 서버가 unhealthy로 교체된다. 관리 포트만 보면
    # "8080만 멈춘" 고장을 놓치는데, 그 빈칸은 liveness 그룹의 requestProgress가 메운다.
    port                = var.management_port
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = {
    Name = "${var.name_prefix}-tg"
  }
}

# 80은 서비스하지 않고 전부 443으로 넘긴다. 브라우저는 주소창에 도메인만 치면 http로 먼저
# 오므로 이 리다이렉트가 없으면 접속이 실패한 것처럼 보인다.
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  certificate_arn   = var.acm_certificate_arn

  # TLS 1.2 미만을 받지 않는 정책. 이 앱의 클라이언트는 Android 앱과 브라우저라 1.3/1.2로
  # 충분하고, 구형 클라이언트 호환을 위해 더 낮출 이유가 없다.
  ssl_policy = "ELBSecurityPolicy-TLS13-1-2-2021-06"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}

# /actuator/*를 인터넷에서 차단한다.
#
# 앱은 SecurityConfig에서 /actuator/**를 permitAll로 두고 있고(그래야 ALB 헬스체크가 인증 없이
# 통과한다), application.yml이 health·metrics·prometheus 세 엔드포인트를 노출하며
# show-details: always다. 즉 앱 자체는 이 정보를 누구에게나 준다 — 외부 차단은 여기서 한다.
#
# 조건에 "/actuator"와 "/actuator/*"를 둘 다 넣는다. 후자만 넣으면 슬래시 없는 /actuator 요청이
# 규칙을 비껴가 그대로 앱에 도달한다.
#
# Swagger(/swagger-ui/**, /v3/api-docs/**)는 차단하지 않는다 — Android FE 팀이 쓰는 API
# 문서이기 때문이다(CLAUDE.md). 이 규칙이 Swagger를 건드리지 않는지는 판정 기준 P3으로 확인한다.
#
# 한계: 리스너 규칙은 ALB를 지나는 트래픽에만 적용된다. enable_dev_direct_access를 켜서
# 8080으로 직접 붙으면 이 차단을 지나친다.
#
# actuator는 이제 관리 포트(8081)로 분리돼 8080에는 /actuator가 없다(#198). 그래도 이 차단을
# 지우지 않는다 — prod 프로필이 빠진 채 뜨면(local로 뜨면) actuator가 다시 8080에 붙는다.
# 프로필 누락은 앱이 정상처럼 보이면서 조용히 일어나는 사고라(docs/guide/profile.md), 그때도
# 외부 노출만은 막혀 있어야 한다.
#
# /internal도 같은 규칙에서 막는다. 헬스체크의 8080 직접 확인(/internal/ping)과 측정 전용 결함
# 주입 장치(/internal/fault/**)가 쓰는 경로이고, 둘 다 인스턴스 안에서만 부른다. 결함 주입 장치는
# 측정 때만 켜지지만, 켜진 동안 인터넷에서 워커를 묶을 수 있게 되면 그 자체가 장애 경로다.
# 규칙 하나의 조건 값은 최대 5개라 넷까지는 한 규칙에 둔다.
resource "aws_lb_listener_rule" "block_actuator" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 1

  condition {
    path_pattern {
      values = ["/actuator", "/actuator/*", "/internal", "/internal/*"]
    }
  }

  action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "Forbidden"
      status_code  = "403"
    }
  }
}
