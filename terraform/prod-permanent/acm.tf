# ALB에서 TLS를 종단하기 위한 인증서.
#
# 리전 주의: ALB는 리전 리소스라 인증서도 ALB와 같은 리전(ap-northeast-2)에 있어야 한다.
# 이 저장소에는 CloudFront용 인증서 요구사항(us-east-1 전용)이 별도로 존재할 수 있으므로,
# 두 인증서가 공존할 때 리전을 혼동하지 않도록 여기 명시해 둔다.
resource "aws_acm_certificate" "this" {
  domain_name = var.domain_name

  # 와일드카드를 SAN으로 함께 받아둔다. 지금은 apex만 쓰지만 나중에 api./cdn. 같은
  # 서브도메인을 붙일 때 인증서를 재발급하지 않아도 된다. DNS 검증 레코드는 apex와
  # 와일드카드가 같은 이름을 쓰므로 추가 레코드가 생기지 않는다(아래 for_each의 중복 제거).
  subject_alternative_names = ["*.${var.domain_name}"]

  # 이메일 검증은 도메인 등록 시 기재한 주소로 사람이 클릭해야 하고 갱신 때마다 반복된다.
  # DNS 검증은 레코드가 존에 남아 있는 한 자동 갱신되므로, 이 저장소의 "수동 절차 제로화"
  # 원칙(docs/guide/profile.md §4-6)에 맞는다.
  validation_method = "DNS"

  # 인증서를 교체할 때 ALB 리스너가 참조하는 기존 인증서를 먼저 지우면 리스너가 깨진다.
  # 새 인증서를 먼저 만들고 참조를 옮긴 뒤 옛것을 지우게 한다.
  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Name = "${var.name_prefix}-cert"
  }
}

# 검증용 CNAME 레코드. apex와 와일드카드가 동일한 검증 이름을 공유하므로 for_each의 키를
# domain_validation_options의 이름으로 잡아 중복을 자연스럽게 제거한다(그냥 리스트로 돌리면
# 같은 레코드를 두 번 만들려다 충돌한다).
resource "aws_route53_record" "cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.this.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }

  zone_id = aws_route53_zone.this.zone_id
  name    = each.value.name
  type    = each.value.type
  records = [each.value.record]
  ttl     = 60

  # 검증 레코드는 인증서를 다시 만들면 값이 바뀐다. 같은 이름의 기존 레코드가 있으면
  # apply가 실패하는데, 이 레코드는 terraform이 유일한 관리자이므로 덮어쓰는 편이 맞다.
  allow_overwrite = true
}

# 검증이 끝날 때까지 apply를 붙잡아 둔다. 이게 없으면 terraform/prod/의 ALB 리스너가
# 아직 PENDING_VALIDATION인 인증서를 참조해 apply가 실패한다.
#
# ⚠️ 가비아 네임서버 위임이 끝나기 전에 이 리소스를 apply하면, Route53에 레코드는 들어가지만
#    외부에서 조회되지 않아 ACM이 검증을 완료하지 못한다. 기본 타임아웃(75분)을 다 기다린 뒤
#    실패하므로, README의 순서대로 dig으로 위임을 먼저 확인하고 apply해야 한다.
resource "aws_acm_certificate_validation" "this" {
  certificate_arn         = aws_acm_certificate.this.arn
  validation_record_fqdns = [for r in aws_route53_record.cert_validation : r.fqdn]
}
