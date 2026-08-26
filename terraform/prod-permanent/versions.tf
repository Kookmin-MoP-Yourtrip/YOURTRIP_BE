# 기존 terraform/versions.tf·terraform/loadtest/versions.tf와 동일한 버전 고정 정책을 따른다 —
# 세 루트 모듈이 서로 다른 시점에 apply되더라도 동일한 provider 동작을 보장하기 위함이다.
terraform {
  # 하한을 1.11.0으로 올린 근거는 terraform/versions.tf 주석에 있다(#157, native lockfile).
  required_version = ">= 1.11.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }
}
