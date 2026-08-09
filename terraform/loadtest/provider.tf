provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project   = "yourtrip"
      Component = "loadtest"
      ManagedBy = "terraform"
      # 이 인프라는 측정 후 즉시 destroy하는 일회성 환경임을 태그로도 명시해,
      # 콘솔에서 실수로 영구 리소스로 착각하고 남겨두는 걸 방지한다.
      Ephemeral = "true"
    }
  }
}
