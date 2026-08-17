# ============================================================
# 공통
# ============================================================

variable "aws_region" {
  description = "리전. 기존 terraform/variables.tf의 S3/CloudFront 리전과 동일하게 맞춘다."
  type        = string
  default     = "ap-northeast-2"
}

variable "name_prefix" {
  description = "이 모듈이 생성하는 모든 리소스 이름/태그 접두사. 기존 terraform/(영구 인프라)과 이름이 겹치지 않게 구분한다."
  type        = string
  default     = "yourtrip-loadtest"
}

variable "my_ip_cidr" {
  description = "개발자의 현재 공인 IP를 CIDR 형식(예: 1.2.3.4/32)으로 지정한다. `curl -s ifconfig.me`로 확인. SSH·8080(actuator 원격 스크레이프)·(선택) 5432 접근을 이 IP로만 제한하는 데 쓰인다."
  type        = string
}

# ============================================================
# 네트워크 — 계정의 기본 VPC 존재 여부에 의존하지 않는 전용 VPC
# ============================================================

variable "vpc_cidr" {
  description = "이번 부하테스트 전용 VPC의 CIDR."
  type        = string
  default     = "10.42.0.0/16"
}

variable "public_subnet_primary_cidr" {
  description = "App/k6 EC2와 RDS/ElastiCache가 실제로 배치되는 주 서브넷. 모든 컴퓨트 리소스를 여기 한 AZ에 몰아 네트워크 변수를 최소화한다."
  type        = string
  default     = "10.42.1.0/24"
}

variable "public_subnet_secondary_cidr" {
  description = "RDS/ElastiCache 서브넷 그룹이 AWS 제약상 요구하는 두 번째 AZ용 서브넷. 실제 인스턴스는 배치하지 않는다."
  type        = string
  default     = "10.42.2.0/24"
}

variable "availability_zone_primary" {
  description = "App/k6 EC2, RDS, ElastiCache를 전부 이 AZ로 고정한다."
  type        = string
  default     = "ap-northeast-2a"
}

variable "availability_zone_secondary" {
  description = "RDS/ElastiCache 서브넷 그룹용 2번째 AZ (실사용 없음)."
  type        = string
  default     = "ap-northeast-2c"
}

# ============================================================
# EC2 — App / k6
# ============================================================

variable "ssh_public_key_path" {
  description = "EC2 SSH 접속용 공개키(.pub) 파일 경로. 이번 테스트 전용 신규 키페어이며 CloudFront 서명 키페어와는 무관하다."
  type        = string
}

variable "app_instance_type" {
  description = "App EC2 인스턴스 타입. 실제 배포 타겟(PRESIGN-BOTTLENECK.md 확인)과 동일한 t3.micro가 기본값 — '운영 스펙이 이 부하를 버티는가'를 그대로 검증하기 위함."
  type        = string
  default     = "t3.micro"
}

variable "k6_instance_type" {
  description = "k6 부하생성기 EC2 인스턴스 타입. Redis를 ElastiCache로 분리해 이 인스턴스와 경합할 상대가 없어 프리티어 t3.micro를 기본값으로 둔다. 테스트 중 CPUUtilization이 포화되면 t3.small로 올린다."
  type        = string
  default     = "t3.micro"
}

variable "enable_detailed_monitoring" {
  description = "true면 CloudWatch 1분 단위 지표(기본은 5분)를 활성화한다. 부하테스트가 7.5분(450초)으로 짧아 5분 간격으로는 knee 시점을 정확히 못 잡는다 — 인스턴스당 소액 과금이 있지만(월 약 $2.1 수준, 이번처럼 몇 시간만 띄우면 사실상 센트 단위) 측정 해상도를 위해 기본값을 true로 둔다."
  type        = bool
  default     = true
}

variable "app_repo_url" {
  description = "k6 EC2가 user_data에서 git fetch할 저장소 URL(부하 스크립트 checkout용). App EC2는 더 이상 이 값을 쓰지 않는다 — JAR는 로컬 빌드 후 scp로 전달한다(templates/app-user-data.sh.tpl 상단 주석 참고)."
  type        = string
  default     = "https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE.git"
}

variable "app_git_ref" {
  description = "k6 EC2가 checkout할 브랜치 또는 커밋(부하 스크립트 버전 고정용). App EC2에 올릴 JAR도 로컬에서 이 커밋을 기준으로 빌드해야 두 인스턴스가 같은 코드 상태를 테스트한다."
  type        = string
}

# ============================================================
# RDS
# ============================================================

variable "rds_instance_class" {
  description = "RDS 인스턴스 클래스. db.t3.micro는 프리티어 대상이며, 로컬 시드 규모(course 6,000행 등)에 충분히 여유 있다. 버스터블 계열이라 CPUCreditBalance를 반드시 함께 모니터링한다."
  type        = string
  default     = "db.t3.micro"
}

variable "rds_engine_version" {
  description = "PostgreSQL major 버전. apply 전 로컬 `psql --version`으로 실제 로컬 버전을 확인해 맞추는 걸 권장한다(로컬 대비 배율 비교의 기준을 맞추기 위함). major 버전만 지정하면 AWS가 마이너 버전은 자동으로 최신을 선택한다."
  type        = string
  default     = "16"
}

variable "rds_db_name" {
  description = "생성할 데이터베이스 이름. 로컬 개발과 동일하게 yourtrip을 기본값으로 둔다."
  type        = string
  default     = "yourtrip"
}

variable "rds_username" {
  description = "RDS 마스터 사용자명."
  type        = string
  default     = "postgres"
}

variable "rds_password" {
  description = "RDS 마스터 비밀번호. 기본값을 두지 않아 terraform.tfvars에서 반드시 지정하도록 강제한다."
  type        = string
  sensitive   = true
}

# ============================================================
# ElastiCache
# ============================================================

variable "elasticache_node_type" {
  description = "ElastiCache Redis 노드 타입. cache.t3.micro는 프리티어 대상(월 750시간, 첫 12개월 — AWS 콘솔 Billing에서 계정별 자격 확인 필요)."
  type        = string
  default     = "cache.t3.micro"
}

# ============================================================
# 애플리케이션 환경변수 (.env.example 대응)
# 대부분 기존 terraform/(S3·CloudFront·IAM)의 apply 결과(outputs)를 그대로 재사용한다 —
# 이 모듈이 그 리소스들을 새로 만들지는 않는다.
# ============================================================

variable "jwt_secret" {
  type      = string
  sensitive = true
}

variable "mail_email" {
  type = string
}

variable "mail_password" {
  type      = string
  sensitive = true
}

variable "kakao_api_key" {
  type      = string
  sensitive = true
}

variable "s3_bucket" {
  description = "기존 terraform/의 output s3_bucket_name 값을 그대로 사용."
  type        = string
}

variable "aws_access_key" {
  description = "기존 terraform/의 output iam_user_access_key_id 값을 그대로 사용."
  type        = string
  sensitive   = true
}

variable "aws_secret_key" {
  description = "기존 terraform/의 output iam_user_secret_access_key 값을 그대로 사용."
  type        = string
  sensitive   = true
}

variable "cloudfront_domain" {
  description = "기존 terraform/의 output cloudfront_domain_name 값을 그대로 사용."
  type        = string
}

variable "cloudfront_key_pair_id" {
  description = "기존 terraform/의 output cloudfront_key_pair_id 값을 그대로 사용."
  type        = string
}

variable "cloudfront_distribution_id" {
  description = "기존 terraform/의 output cloudfront_distribution_id 값을 그대로 사용."
  type        = string
}

variable "gemini_api_key" {
  type      = string
  sensitive = true
}
