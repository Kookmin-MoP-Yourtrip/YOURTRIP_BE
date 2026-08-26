# state를 S3에 둔다(#157). 버킷을 하나만 두고 모듈별로 key를 나눈다 — 잠금은 key 단위라
# 모듈끼리 간섭하지 않는다. 설계 근거와 버킷 이름을 하드코딩하는 이유는
# terraform/backend.tf 주석에 정리돼 있다.
terraform {
  backend "s3" {
    bucket       = "yourtrip-tfstate-520426835144"
    key          = "prod/terraform.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true
  }
}
