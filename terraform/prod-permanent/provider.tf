provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project   = "yourtrip"
      Component = "prod"
      ManagedBy = "terraform"
      # loadtest 모듈과 달리 Ephemeral 태그를 붙이지 않는다. 이 모듈의 리소스(도메인·인증서·
      # 배포 아티팩트)는 destroy 대상이 아니며, 그 사실을 콘솔에서도 구분할 수 있어야 한다.
      # 실제로 매번 destroy되는 것은 terraform/prod/ 쪽이고 그쪽에는 Ephemeral=true가 붙는다.
      Lifecycle = "permanent"
    }
  }
}
