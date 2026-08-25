# ============================================================
# 공통
# ============================================================

variable "aws_region" {
  description = "리전. terraform/prod-permanent/의 ACM 인증서와 반드시 같아야 한다 — ALB는 리전 리소스라 다른 리전의 인증서를 붙일 수 없다."
  type        = string
  default     = "ap-northeast-2"
}

variable "name_prefix" {
  description = "이 모듈이 생성하는 모든 리소스 이름/태그 접두사. 기존 terraform/(영구)·terraform/loadtest/·terraform/prod-permanent/와 겹치지 않게 구분한다."
  type        = string
  default     = "yourtrip-prod"
}

variable "my_ip_cidr" {
  description = "개발자의 현재 공인 IP를 CIDR 형식(예: 1.2.3.4/32)으로 지정한다. `curl -s ifconfig.me`로 확인. SSH(break-glass)와, enable_dev_direct_access를 켰을 때의 8080 직접 접근을 이 IP로만 제한하는 데 쓰인다."
  type        = string
}

variable "enable_dev_direct_access" {
  description = "개발자 IP에서 ALB를 우회해 EC2:8080으로 직접 접근할지 여부. 기본은 끈다 — 운영에서는 모든 트래픽이 ALB를 통과해야 /actuator 차단 규칙이 의미를 갖는다. 디버깅 때만 일시적으로 켠다."
  type        = bool
  default     = false
}

# ============================================================
# 네트워크 — 계정의 기본 VPC에 의존하지 않는 전용 VPC
# ============================================================

variable "vpc_cidr" {
  description = "운영 전용 VPC의 CIDR. terraform/loadtest/(10.42.0.0/16)와 겹치지 않게 잡는다 — 두 환경이 동시에 떠 있어도 나중에 피어링/전환을 검토할 여지를 남긴다."
  type        = string
  default     = "10.43.0.0/16"
}

variable "public_subnet_primary_cidr" {
  description = "App EC2와 RDS/ElastiCache가 실제로 배치되는 주 서브넷. 컴퓨트와 데이터 계층을 한 AZ에 몰아 AZ 횡단 왕복 비용을 없앤다."
  type        = string
  default     = "10.43.1.0/24"
}

variable "public_subnet_secondary_cidr" {
  description = "두 번째 AZ 서브넷. loadtest에서는 RDS/ElastiCache 서브넷 그룹의 '최소 2 AZ' 제약을 채우는 더미였지만, 여기서는 ALB가 최소 2개 AZ의 서브넷을 요구하므로 실제로 쓰인다."
  type        = string
  default     = "10.43.2.0/24"
}

variable "availability_zone_primary" {
  description = "App EC2·RDS·ElastiCache를 모두 배치할 AZ. 셋이 같은 AZ에 있어야 한다 — ElastiCache가 다른 AZ에 떨어져 Redis 명령 지연 바닥값이 0.2~0.4ms에서 1.2ms로 굳은 사고 기록이 elasticache.tf 주석에 있다."
  type        = string
  default     = "ap-northeast-2a"
}

variable "availability_zone_secondary" {
  description = "ALB와 서브넷 그룹이 요구하는 두 번째 AZ. 앱 인스턴스는 여기 배치하지 않는다."
  type        = string
  default     = "ap-northeast-2c"
}

# ============================================================
# EC2 접근 — SSH 키페어(break-glass용)
# ============================================================

variable "ssh_public_key_path" {
  description = "EC2 SSH 접속용 공개키(.pub) 경로. 개인키는 terraform 밖에서 만들고 공개키만 등록해 state에 개인키가 들어가지 않게 한다(예: ssh-keygen -t ed25519 -f ./yourtrip-prod-ssh -C yourtrip-prod). 평상시 접속은 SSM Session Manager를 쓰고, 이 키는 SSM이 안 될 때의 탈출구다."
  type        = string
  default     = "./yourtrip-prod-ssh.pub"
}

# ============================================================
# 시크릿·아티팩트 — terraform 밖에서 관리되는 것들을 참조만 한다
# ============================================================

variable "ssm_parameter_path" {
  description = "앱 시크릿이 SecureString으로 저장된 SSM Parameter Store 경로 접두사. 이 경로의 파라미터는 terraform이 만들지 않는다 — aws_ssm_parameter 리소스로 만들면 값이 tfstate에 평문으로 남아, 시크릿을 user_data에서 걷어낸 의미가 사라진다."
  type        = string
  default     = "/yourtrip/prod"
}

variable "artifact_bucket_name" {
  description = "배포 JAR이 있는 S3 버킷 이름. terraform -chdir=../prod-permanent output -raw artifact_bucket_name 으로 얻는다."
  type        = string
}

# ============================================================
# RDS (PostgreSQL)
# ============================================================

variable "rds_instance_class" {
  description = "RDS 인스턴스 클래스. 부하테스트 실측이 db.t3.micro에서 이뤄졌으므로 그 환경과 동형을 유지한다."
  type        = string
  default     = "db.t3.micro"
}

variable "rds_engine_version" {
  description = "PostgreSQL 메이저 버전만 지정한다 — 마이너는 AWS가 자동으로 최신을 고르게 둬서 불필요한 버전 고정 관리를 피한다."
  type        = string
  default     = "16"
}

variable "rds_allocated_storage" {
  description = "스토리지 크기(GB). 프리티어 상한과 맞춘다."
  type        = number
  default     = 20
}

variable "rds_db_name" {
  description = "생성할 데이터베이스 이름. 앱의 DB_URL 마지막 경로와 일치해야 한다."
  type        = string
  default     = "yourtrip"
}

variable "rds_username" {
  description = "마스터 사용자 이름."
  type        = string
  default     = "postgres"
}

variable "rds_deletion_protection" {
  description = "삭제 보호. 기본은 끈다 — 이 환경은 데모·측정이 끝나면 destroy하는 온디맨드 모델이라, 켜면 destroy가 막혀 apply를 두 번 해야 한다. AWS 기본값과 같은 값이지만 '방치된 기본값'이 아니라 '검토 후 끈 값'임을 코드로 드러내려고 변수로 노출한다. 상시 가동으로 전환한다면 여기를 true로 뒤집는다."
  type        = bool
  default     = false
}

variable "rds_final_snapshot_suffix" {
  description = "비우면 최종 스냅샷 없이 destroy한다(기본, 빠른 철거). 값을 넣으면 그 접미사로 최종 스냅샷을 남긴다 — 데이터를 보존하고 싶은 날에만 destroy 시점에 -var로 넘긴다. 예: terraform destroy -var 'rds_final_snapshot_suffix=2026-08-25-demo'"
  type        = string
  default     = ""
}

# ============================================================
# ElastiCache (Redis)
# ============================================================

variable "elasticache_node_type" {
  description = "ElastiCache 노드 타입. 부하테스트 실측이 cache.t3.micro에서 이뤄졌으므로 동형을 유지한다."
  type        = string
  default     = "cache.t3.micro"
}
