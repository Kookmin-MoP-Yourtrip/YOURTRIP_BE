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
  description = <<-EOT
    앱 시크릿이 SecureString으로 저장된 SSM Parameter Store 경로 접두사. 아래 두 갈래로 나눠 쓴다.

      <path>/env/<KEY>              .env에 KEY=VALUE 한 줄로 들어갈 값들
      <path>/cloudfront_private_key  파일로 떨어져야 하는 PEM(여러 줄이라 .env에 넣을 수 없다)

    env/ 하위만 일괄 조회해 .env를 만들기 때문에 이 분리가 필요하다 — 한 경로에 섞으면 PEM의
    개행이 .env를 깨뜨린다.

    이 경로의 파라미터는 terraform이 만들지 않는다. aws_ssm_parameter 리소스로 만들면 값이
    tfstate에 평문으로 남아 시크릿을 user_data에서 걷어낸 의미가 사라지고, terraform 밖에 있어야
    destroy가 지우지 않아 재apply 때 재입력이 불필요하다.
  EOT
  type        = string
  default     = "/yourtrip/prod"
}

variable "artifact_bucket_name" {
  description = "배포 JAR이 있는 S3 버킷 이름. terraform -chdir=../prod-permanent output -raw artifact_bucket_name 으로 얻는다."
  type        = string
}

# ============================================================
# 도메인 / DNS
# ============================================================

variable "domain_name" {
  description = "운영 도메인(apex). terraform/prod-permanent/의 domain_name과 같아야 한다 — 그 모듈이 만든 호스티드존을 여기서 data로 읽어 alias 레코드를 넣는다."
  type        = string
  default     = "yourtrip.cloud"
}

variable "enable_dns_record" {
  description = "도메인이 이 환경의 ALB를 가리키게 할지 여부. 기본은 끈다 — 검증을 마치기 전에 켜면 아직 확인되지 않은 환경으로 실트래픽이 넘어간다. 검증 배터리를 통과한 뒤 true로 바꿔 apply하는 것이 DNS 전환 절차 그 자체다."
  type        = bool
  default     = false
}

# ============================================================
# ALB — TLS 종단과 진입점
# ============================================================

variable "acm_certificate_arn" {
  description = "HTTPS 리스너에 붙일 ACM 인증서 ARN. terraform -chdir=../prod-permanent output -raw acm_certificate_arn 으로 얻는다. 인증서 리전이 var.aws_region과 같아야 한다."
  type        = string
}

variable "alb_idle_timeout" {
  description = "ALB가 유휴 연결을 끊기까지의 초. 백엔드(Tomcat) keep-alive보다 짧아야 한다 — 더 길면 ALB가 Tomcat이 방금 닫은 연결을 재사용하려다 간헐 502를 만든다. application-prod.yml의 keep-alive-timeout(65s)이 이 값보다 크게 잡혀 있다."
  type        = number
  default     = 60
}

variable "target_group_deregistration_delay" {
  description = "타깃을 뺄 때 기존 연결을 기다리는 초. AWS 기본값 300은 온디맨드 모델에서 scale-in과 destroy를 매번 5분씩 늘린다. 짧게 잡은 대가로 진행 중인 장기 요청이 잘릴 수 있다."
  type        = number
  default     = 30
}

variable "health_check_path" {
  description = "타깃 그룹 헬스체크 경로. /actuator/health가 아니라 liveness를 쓰는 이유: 전자는 DB·Redis가 내려가면 503을 반환하는데, health_check_type=ELB인 ASG는 그 인스턴스를 죽이고 다시 띄우기를 반복한다 — RDS 순간 장애 하나가 인스턴스 교체 폭풍이 된다. liveness는 프로세스 생존만 본다."
  type        = string
  default     = "/actuator/health/liveness"
}

# ============================================================
# EC2 / ASG
# ============================================================

variable "app_instance_type" {
  description = "앱 인스턴스 타입. threads.max=32와 -Xmx768m이 t3.small(vCPU 2, 2GB)에서 실측으로 도출된 값이라 그 환경을 유지한다."
  type        = string
  default     = "t3.small"
}

variable "app_ami_id" {
  description = "비우면 최신 Amazon Linux 2023 AMI를 자동으로 고른다(기본). 측정이나 장시간 데모처럼 환경을 고정해야 할 때는 직전 apply의 app_ami_id 출력값을 여기 박아 재현성을 확보한다."
  type        = string
  default     = ""
}

variable "app_root_volume_size" {
  description = "루트 EBS 크기(GB). JAR + JVM + 로그만 올리므로 작게 잡는다."
  type        = number
  default     = 10
}

variable "asg_min_size" {
  description = "ASG 최소 인스턴스 수."
  type        = number
  default     = 1
}

variable "asg_max_size" {
  description = "ASG 최대 인스턴스 수. 2인 이유는 스케일아웃 실증과 무중단 교체(instance refresh가 먼저 띄우고 나중에 죽이는 순서를 쓰려면 여유 슬롯이 필요하다) 양쪽에 최소한으로 필요한 값이기 때문이다."
  type        = number
  default     = 2
}

variable "asg_desired_capacity" {
  description = "평상시 인스턴스 수."
  type        = number
  default     = 1
}

variable "scaleout_request_count_per_target_per_minute" {
  description = <<-EOT
    Target Tracking 임계값. ⚠️ ALBRequestCountPerTarget은 60초 SUM이므로 이 값은 초당이 아니라
    타깃당 '분당' 요청 수다 — 초당 값을 넣으면 임계값이 60배 낮아져 상시 스케일아웃이 된다.

    30000 = 초당 500. 유도 과정: 열린 루프 실측 상한 2,033 req/s에서 출발해,
    측정되지 않은 경로(AI 코스 생성·미디어 업로드) 보정 ×0.5, 스케일아웃 리드타임(3~5분) 보정 ×0.5.
    두 계수는 실측이 아니라 추정이며 그 사실이 docs/tasks/prod-infra-iac/README.md에 적혀 있다.

    실트래픽이 사실상 0이라 이 임계값은 k6를 의도적으로 돌려야 도달한다. 데모용으로 낮춘다면
    그 값과 이유를 verification.md에 함께 기록한다 — 사전 등록한 기준을 조용히 바꾸지 않는다.
  EOT
  type        = number
  default     = 30000
}

variable "asg_health_check_grace_period" {
  description = "인스턴스 기동 후 헬스체크 실패를 무시하는 초. 부팅 + dnf install + JAR 다운로드 + Spring 기동까지 걸리는 시간이다."
  type        = number
  default     = 300
}

variable "asg_instance_warmup" {
  description = "새 인스턴스가 지표에 반영되기까지 기다리는 초. JIT 예열 때문에 필요하다 — 재기동 직후 첫 고부하는 정상 대비 처리량이 23% 낮게 나오고(docs/tasks/tomcat-thread-sizing/ec2-measurement.md), 그 낮은 처리량이 지표를 오염시키면 불필요한 추가 스케일아웃을 부른다."
  type        = number
  default     = 300
}

variable "enable_detailed_monitoring" {
  description = "EC2 상세 모니터링(1분 간격). 기본 5분 간격은 스케일 반응과 실측 분석 양쪽에 너무 성기다."
  type        = bool
  default     = true
}

# ============================================================
# 앱 환경변수 — 비밀이 아닌 값만 여기 둔다
#
# 비밀(DB_PASSWORD·JWT_SECRET·API 키 등)은 tfvars에 넣지 않는다. SSM Parameter Store에
# SecureString으로 넣고 인스턴스가 부팅 시 직접 받아간다 — README의 "시크릿은 어디에 있는가" 참고.
# ============================================================

variable "db_ddl_auto" {
  description = <<-EOT
    Hibernate ddl-auto. create가 아니라 update인 이유: create는 SessionFactory가 뜰 때마다
    스키마를 DROP+CREATE하는데, ASG는 사람 개입 없이 인스턴스를 띄우므로 스케일아웃으로 두 번째
    인스턴스가 뜨는 순간 첫 번째가 쓰던 테이블이 통째로 재생성된다. 진행 중이던 요청이 깨지고,
    이 이슈의 핵심 데모인 스케일아웃 실증 도중에 500이 난다.

    update의 한계(컬럼 삭제·타입 변경 미반영)는 실서비스를 하지 않는다는 전제 위에서 수용한 것이다.
  EOT
  type        = string
  default     = "update"
}

variable "s3_bucket" {
  description = "앱이 미디어를 올리는 S3 버킷. terraform -chdir=../ output -raw s3_bucket_name"
  type        = string
}

variable "cloudfront_domain" {
  description = "terraform -chdir=../ output -raw cloudfront_domain_name"
  type        = string
}

variable "cloudfront_key_pair_id" {
  description = "terraform -chdir=../ output -raw cloudfront_key_pair_id"
  type        = string
}

variable "cloudfront_distribution_id" {
  description = "terraform -chdir=../ output -raw cloudfront_distribution_id"
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
