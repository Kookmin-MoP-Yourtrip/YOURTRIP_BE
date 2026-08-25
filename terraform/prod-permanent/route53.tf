# 도메인을 가비아에서 구매했으므로 호스티드존이 존재하지 않는다 — 여기서 직접 만든다.
#
# ⚠️ Route 53 Registrar에서 도메인을 샀다면 이 리소스를 쓰면 안 된다. 그 경우 호스티드존이
#    등록과 동시에 자동 생성되는데, 여기서 aws_route53_zone을 또 만들면 NS 세트가 다른
#    두 번째 존이 생기고 등록기관은 첫 번째 존을 계속 가리킨다. 레코드는 정상적으로 들어가는데
#    외부에서 조회가 안 되는, 원인을 찾기 어려운 형태로 DNS가 조용히 실패한다.
#    그 경우엔 resource가 아니라 data "aws_route53_zone"으로 기존 존을 읽어야 한다.
#
# 이 존이 영구 state에 있는 이유: destroy하면 NS 세트가 바뀌어 가비아 콘솔에서 네임서버를
# 다시 입력하고 전파를 다시 기다려야 한다. 온디맨드로 apply/destroy를 반복하는
# terraform/prod/와 수명이 다르므로 state를 분리했다(docs/tasks/prod-infra-iac/README.md).
resource "aws_route53_zone" "this" {
  name = var.domain_name

  tags = {
    Name = "${var.name_prefix}-zone"
  }
}
