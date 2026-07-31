provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project   = "yourtrip"
      ManagedBy = "terraform"
    }
  }
}
