# 이 구성이 검증된 Terraform/Provider 버전 범위를 명시한다.
# required_version 상한(< 2.0.0)은 2.x에서 발생할 수 있는 breaking change로부터
# "이 구성은 1.x에서만 검증됐다"는 사실을 명시적으로 선언하기 위함이다.
terraform {
  required_version = ">= 1.9.0, < 2.0.0"

  required_providers {
    aws = {
      source = "hashicorp/aws"
      # 마이너 버전 자동 업데이트는 허용하되 메이저 버전(6.x) 자동 승격은 막는다.
      version = "~> 5.60"
    }
  }
}
