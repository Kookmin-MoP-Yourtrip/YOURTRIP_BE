# 운영 도메인이 이 환경의 ALB를 가리키게 하는 A 레코드(alias).
#
# 이 레코드가 영구 state(terraform/prod-permanent/)가 아니라 여기 있는 것이 의도다.
# ALB는 apply마다 새로 만들어져 DNS명이 바뀌는데, 레코드가 같은 state에서
# aws_lb.this.dns_name을 참조하므로 apply할 때마다 자동으로 새 ALB를 가리킨다.
# 영구 쪽에 두면 매번 손으로 고쳐야 한다.
#
# destroy하면 도메인이 NXDOMAIN이 된다. 온디맨드 모델에서 "서버가 내려가 있다"는 사실의
# 정직한 반영이라 허용한다(호스티드존 자체는 영구 state에 남아 위임이 유지된다).
data "aws_route53_zone" "this" {
  name = var.domain_name
}

# alias 레코드는 TTL을 지정할 수 없다 — AWS가 60초를 쓴다. 그래서 ALB가 교체돼도
# 전환 지연이 1분 이내다.
resource "aws_route53_record" "apex" {
  count = var.enable_dns_record ? 1 : 0

  zone_id = data.aws_route53_zone.this.zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name                   = aws_lb.this.dns_name
    zone_id                = aws_lb.this.zone_id
    evaluate_target_health = false
  }
}
