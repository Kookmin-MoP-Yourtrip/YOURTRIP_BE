variable "aws_region" {
  description = "S3 버킷을 생성할 리전. application.yml의 s3.region 하드코딩 값(ap-northeast-2)과 반드시 일치해야 한다."
  type        = string
  default     = "ap-northeast-2"
}

variable "bucket_name" {
  description = "S3 버킷 이름. 전역적으로 고유해야 하므로 기본값을 두지 않고 tfvars에서 반드시 지정하도록 강제한다."
  type        = string
}

variable "iam_user_name" {
  description = "애플리케이션이 S3 접근에 사용할 프로그래매틱 전용 IAM 사용자 이름."
  type        = string
  default     = "yourtrip-s3-app-user"
}

variable "grant_list_bucket" {
  description = "S3Service.java는 현재 ListBucket 계열 API를 호출하지 않는다. 최소 권한 원칙에 따라 기본은 false이며, 추후 파일 목록 조회 기능이 추가되면 true로 전환한다."
  type        = bool
  default     = false
}

variable "bucket_force_destroy" {
  description = "true로 두면 버킷에 오브젝트가 남아있어도 terraform destroy가 강제로 삭제한다. 실수로 인한 데이터 유실을 막기 위해 기본값은 false."
  type        = bool
  default     = false
}

variable "cloudfront_public_key_path" {
  description = "mycourse Signed URL 검증에 쓸 ECDSA P-256 공개키(PEM) 파일 경로. openssl로 생성한 키페어 중 공개키만 이 경로에 둔다(terraform/README.md 절차 참고) — 개인키는 이 저장소나 tfstate에 절대 들어가지 않는다."
  type        = string
}

variable "cloudfront_price_class" {
  description = "CloudFront가 실제로 요청을 서빙할 엣지 로케이션 범위 겸 과금 등급. PriceClass_200은 북미/유럽에 아시아(한국 포함)까지 포함해 이 앱의 주 사용자층에 필요한 리전을 커버하면서 PriceClass_All보다 저렴하다."
  type        = string
  default     = "PriceClass_200"
}
