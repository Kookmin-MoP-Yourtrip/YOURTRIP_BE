# ============================================================
# 공통
# ============================================================

variable "aws_region" {
  description = "리전. ALB용 ACM 인증서는 ALB와 같은 리전에 있어야 하므로 terraform/prod/와 반드시 일치해야 한다(CloudFront용 인증서가 us-east-1을 요구하는 것과 다른 규칙이다)."
  type        = string
  default     = "ap-northeast-2"
}

variable "name_prefix" {
  description = "이 모듈이 생성하는 리소스 이름/태그 접두사. 기존 terraform/(영구 인프라)·terraform/loadtest/와 겹치지 않게 구분한다."
  type        = string
  default     = "yourtrip-prod"
}

# ============================================================
# 도메인 — 가비아에서 구매한 .com을 Route53으로 위임해 쓴다
# ============================================================

variable "domain_name" {
  description = "운영 도메인(apex). 예: yourtrip-example.com — 앞에 www나 http를 붙이지 않는다. 이 값이 ACM 인증서의 CN이 되고 terraform/prod/의 alias 레코드 대상이 된다."
  type        = string
}

# ============================================================
# 배포 아티팩트 버킷 — ASG 인스턴스가 부팅 시 JAR을 내려받는 곳
# ============================================================

variable "artifact_bucket_name" {
  description = "배포 JAR을 두는 S3 버킷 이름(전역 유일해야 한다). 기존 미디어 버킷을 재사용하지 않는 이유는 README의 '왜 버킷을 새로 만드는가' 절에 있다."
  type        = string
}

variable "artifact_bucket_force_destroy" {
  description = "버킷에 객체가 남아 있어도 destroy를 허용할지 여부. 기본은 차단한다 — 이 버킷은 destroy 대상이 아니고, 실수로 지우면 배포 아티팩트 이력이 통째로 사라진다."
  type        = bool
  default     = false
}
