# 계정의 기본 VPC 존재 여부에 의존하지 않는 완전히 독립된 부하테스트 전용 네트워크.
# RDS/ElastiCache의 서브넷 그룹은 AWS 제약상 최소 2개 AZ의 서브넷이 필요해 서브넷을
# 2개 만들지만, 실제 EC2/RDS/ElastiCache 배치는 전부 var.availability_zone_primary로
# 고정해 인스턴스 간 네트워크 변수(AZ 간 왕복시간)를 최소화한다 — TASK-PRESIGN-BOTTLENECK-FIX.md의
# "같은 리전, 가능하면 같은 VPC/AZ" 권고를 그대로 반영.
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

# App/k6 EC2, RDS, ElastiCache가 실제로 배치되는 주 서브넷.
resource "aws_subnet" "primary" {
  vpc_id                  = aws_vpc.this.id
  cidr_block              = var.public_subnet_primary_cidr
  availability_zone       = var.availability_zone_primary
  map_public_ip_on_launch = true

  tags = {
    Name = "${var.name_prefix}-subnet-primary"
  }
}

# RDS/ElastiCache 서브넷 그룹의 AZ 요구조건을 채우기 위한 서브넷 — 실사용 인스턴스는 없다.
resource "aws_subnet" "secondary" {
  vpc_id                  = aws_vpc.this.id
  cidr_block              = var.public_subnet_secondary_cidr
  availability_zone       = var.availability_zone_secondary
  map_public_ip_on_launch = false

  tags = {
    Name = "${var.name_prefix}-subnet-secondary"
  }
}

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
