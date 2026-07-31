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
