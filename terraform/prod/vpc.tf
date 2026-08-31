# 계정의 기본 VPC 존재 여부에 의존하지 않는 운영 전용 네트워크.
#
# loadtest와 한 가지가 다르다: 거기서는 secondary 서브넷이 RDS/ElastiCache 서브넷 그룹의
# "최소 2 AZ" 제약만 채우는 더미였지만, 여기서는 ALB가 최소 2개 AZ의 서브넷을 요구하므로
# 실제로 트래픽을 받는다. 그래서 map_public_ip_on_launch를 켠다.
#
# 앱 인스턴스(ASG)는 여전히 primary 한 곳에만 배치한다 — RDS·ElastiCache와 같은 AZ에
# 두기 위해서다. AZ를 횡단하면 Redis 명령 지연 바닥값이 0.2~0.4ms에서 1.2ms로 오르는데,
# /popular 히트 1건이 명령 2회라 요청당 약 2.4ms가 순수 왕복 비용으로 붙는다
# (docs/tasks/cache-effect-measurement/redis-io-bottleneck.md).
#
# 이 구조의 대가: 앱이 단일 AZ에 묶여 AZ 장애에 그대로 노출된다. ALB만 2 AZ에 걸쳐 있다.
# 가용성보다 실측 재현성과 지연을 택한 결과이며, docs/tasks/prod-infra-iac/README.md의
# "한계" 절에 명시돼 있다.
resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "${var.name_prefix}-vpc"
  }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-igw"
  }
}

# App EC2(ASG), RDS, ElastiCache가 실제로 배치되는 주 서브넷. ALB도 여기 한 다리를 걸친다.
resource "aws_subnet" "primary" {
  vpc_id                  = aws_vpc.this.id
  cidr_block              = var.public_subnet_primary_cidr
  availability_zone       = var.availability_zone_primary
  map_public_ip_on_launch = true

  tags = {
    Name = "${var.name_prefix}-subnet-primary"
  }
}

# ALB의 두 번째 AZ이자 RDS/ElastiCache 서브넷 그룹의 AZ 요구조건을 채우는 서브넷.
# 앱 인스턴스는 배치하지 않지만 ALB 노드가 실제로 여기 뜨므로 퍼블릭 IP가 필요하다.
resource "aws_subnet" "secondary" {
  vpc_id                  = aws_vpc.this.id
  cidr_block              = var.public_subnet_secondary_cidr
  availability_zone       = var.availability_zone_secondary
  map_public_ip_on_launch = true

  tags = {
    Name = "${var.name_prefix}-subnet-secondary"
  }
}

# 퍼블릭 서브넷 + 보안그룹 격리 구조라 NAT Gateway가 없다. 앱이 외부 API(OpenAI·Kakao·S3·
# SMTP)를 호출해야 하는데, 프라이빗 서브넷으로 옮기면 NAT Gateway가 필요해져 월 고정비가
# 붙는다. 데이터 계층은 publicly_accessible=false + SG 참조로만 격리한다 —
# 즉 실질 경계가 SG 하나뿐이라는 점을 설계 문서에 한계로 적어뒀다.
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }

  tags = {
    Name = "${var.name_prefix}-rt-public"
  }
}

resource "aws_route_table_association" "primary" {
  subnet_id      = aws_subnet.primary.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "secondary" {
  subnet_id      = aws_subnet.secondary.id
  route_table_id = aws_route_table.public.id
}
