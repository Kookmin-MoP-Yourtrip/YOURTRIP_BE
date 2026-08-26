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

# ============================================================
# GitHub Actions — CD가 OIDC로 임시 자격증명을 받는 경로 (#120)
# ============================================================

variable "github_repository" {
  description = "CD 워크플로가 사는 저장소(<org>/<repo>). OIDC 신뢰 정책의 sub 조건에 그대로 들어가므로, 오타가 있으면 AssumeRole이 조용히 거부된다."
  type        = string
  default     = "Kookmin-MoP-Yourtrip/YOURTRIP_BE"
}

variable "github_oidc_provider_arn" {
  description = "이미 이 계정에 GitHub OIDC provider가 있을 때 그 ARN을 넣으면 새로 만들지 않고 재사용한다. 계정당 URL 하나만 존재할 수 있어, 비워두고 apply했다가 EntityAlreadyExists가 나면 기존 ARN을 여기 채운다."
  type        = string
  default     = ""
}

variable "ssm_parameter_path" {
  description = "앱 설정이 사는 SSM 경로 접두사. terraform/prod/의 같은 이름 변수와 반드시 일치해야 한다 — CD 역할이 갱신할 <path>/artifact_key를 여기서 조립한다."
  type        = string
  default     = "/yourtrip/prod"
}
