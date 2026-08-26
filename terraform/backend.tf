# terraform state를 S3에 둔다(#157).
#
# 로컬 파일이던 시절에는 worktree마다 사람이 손으로 state를 복사해야 했고, 한 번이라도
# 빠뜨리면 낡은 state로 apply해 리소스가 중복 생성되거나 destroy가 실제 리소스를 놓쳐
# 과금이 계속되는 drift가 됐다(terraform/loadtest/README.md에 실제 사고 사례가 있다).
#
# 버킷 이름을 여기 하드코딩하는 이유:
#  - backend 블록은 변수를 쓸 수 없다. var.tfstate_bucket_name을 참조할 방법이 없다.
#  - 대안인 backend.hcl 부분 설정(`init -backend-config=`)은 그 파일을 gitignore해야 해서
#    worktree 복사 대상이 네 개 늘어난다 — 이 작업이 없애려던 문제를 형태만 바꿔 되살린다.
#  - 계정 ID는 이미 terraform.tfvars.example과 docs/ 문서에 커밋돼 있어 새 노출이 아니다.
#
# 이 값은 terraform/prod-permanent/variables.tf의 tfstate_bucket_name 기본값과 반드시
# 같아야 한다. 어긋나면 backend가 그 모듈이 만들지 않은 버킷을 가리키게 된다. 대조 방법:
#   terraform -chdir=terraform/prod-permanent output -raw tfstate_bucket_name
#
# use_lockfile은 S3 네이티브 잠금이다. 종래의 DynamoDB 테이블 방식은 쓰지 않는다 —
# 1.11에서 deprecated됐고(versions.tf의 required_version 주석 참고), 이 계정에는 잠금
# 테이블이 하나도 없어 새로 만들 이유도 없다.
terraform {
  backend "s3" {
    bucket       = "yourtrip-tfstate-520426835144"
    key          = "root/terraform.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true
  }
}
