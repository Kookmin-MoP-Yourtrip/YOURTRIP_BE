# 이 구성이 검증된 Terraform/Provider 버전 범위를 명시한다.
#
# required_version 하한(>= 1.11.0)은 backend.tf가 쓰는 S3 native lockfile(use_lockfile)
# 때문이다(#157) — 1.10에서 도입돼 1.11에서 정식이 됐고, 같은 시점에 종래의 DynamoDB
# 잠금(dynamodb_table)이 deprecated됐다. 그 이전 버전으로는 backend 설정이 해석되지 않아
# init 자체가 실패하므로, 하한을 올려 두지 않으면 "선언한 범위에서는 돌지 않는 구성"이 된다.
#
# 상한(< 2.0.0)은 2.x에서 발생할 수 있는 breaking change로부터
# "이 구성은 1.x에서만 검증됐다"는 사실을 명시적으로 선언하기 위함이다.
terraform {
  required_version = ">= 1.11.0, < 2.0.0"

  required_providers {
    aws = {
      source = "hashicorp/aws"
      # 마이너 버전 자동 업데이트는 허용하되 메이저 버전(6.x) 자동 승격은 막는다.
      version = "~> 5.60"
    }
  }
}
