provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project   = "yourtrip"
      Component = "prod"
      ManagedBy = "terraform"
      # 이 모듈의 리소스는 데모·측정이 끝나면 destroy하는 온디맨드 대상이다. 도메인·인증서·
      # 아티팩트 버킷(terraform/prod-permanent/)과 콘솔에서 구분되도록 태그로도 명시한다.
      # Component가 같고 Ephemeral만 다른 이유는 둘이 같은 운영 환경을 이루기 때문이다.
      Ephemeral = "true"
    }
  }
}
