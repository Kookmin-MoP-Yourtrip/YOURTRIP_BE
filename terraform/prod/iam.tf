# App EC2 전용 IAM 역할.
#
# 관리형 정책 1개와 운영에만 필요한 커스텀 정책 1개로 이뤄진다.
#
# CloudWatchAgentServerPolicy는 #121에서 제거됐다. 호스트 지표를 Grafana Alloy가 내장
# node_exporter로 걷어 Grafana Cloud로 직접 보내므로 AWS 쪽 게시 권한이 필요 없어졌다.
# 인스턴스가 Grafana Cloud 토큰을 SSM에서 읽어야 하지만, 그 권한은 아래 ReadAppSecrets가
# /yourtrip/prod/* 와일드카드로 이미 덮고 있어 정책을 새로 붙이지 않는다.
#  - AmazonSSMManagedInstanceCore: Session Manager 접속과 RDS로의 포트포워딩에 필요하다.
#    AL2023 AMI는 SSM Agent가 이미 설치·실행 중이라 이 권한만 있으면 바로 동작한다.
#  - (커스텀) 배포 아티팩트 읽기 + 앱 시크릿 읽기: 아래 참고.
# Auto Scaling이 ALB 타깃 그룹을 검증하고 인스턴스를 띄우려면 서비스 연결 역할이 있어야 한다.
# 보통 계정에서 ASG를 처음 만들 때 AWS가 자동 생성하지만, 그 생성이 비동기라 첫 apply가
# 먼저 실패한다 — 실제로 이 저장소에서 겪었다:
#
#   Access denied when attempting to assume role .../AWSServiceRoleForAutoScaling.
#   Validating load balancer configuration failed.
#
# 재시도하면 그 사이 역할이 만들어져 통과하므로 "한 번 실패하고 다시 하면 되는" 문제로 보이지만,
# 이 저장소를 clone해 자기 계정에 처음 apply하는 사람은 그대로 같은 실패를 겪는다. IaC가
# "받아서 그대로 돌리면 뜬다"를 만족하려면 여기서 보장해야 한다.
#
# 이미 역할이 있는 계정에서는 생성이 InvalidInput(이름 중복)으로 실패하므로, 존재 여부를
# 조회해 없을 때만 만든다. 있는 경우 terraform은 이 역할을 관리하지 않고 destroy도 하지 않는다.
data "aws_iam_roles" "autoscaling_service_linked" {
  name_regex  = "AWSServiceRoleForAutoScaling"
  path_prefix = "/aws-service-role/autoscaling.amazonaws.com/"
}

resource "aws_iam_service_linked_role" "autoscaling" {
  count = length(data.aws_iam_roles.autoscaling_service_linked.names) == 0 ? 1 : 0

  aws_service_name = "autoscaling.amazonaws.com"
  description      = "Managed by terraform/prod. Required for ASG to validate ALB target groups."
}

data "aws_iam_policy_document" "ec2_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "app_ec2" {
  name               = "${var.name_prefix}-app-ec2-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume_role.json
}

resource "aws_iam_role_policy_attachment" "app_ec2_ssm" {
  role       = aws_iam_role.app_ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# 부팅 시 인스턴스가 스스로 해야 하는 두 가지에 대한 최소 권한.
#
# 1) 배포 JAR 다운로드 — ASG는 사람 개입 없이 인스턴스를 띄우므로 loadtest처럼 scp로 JAR을
#    넣을 수 없다. 아티팩트 버킷의 객체만 읽게 한다(다른 버킷·쓰기 권한 없음).
#
# 2) 앱 시크릿 조회 — DB 비밀번호·JWT 시크릿·API 키를 user_data에 평문으로 박지 않기 위해
#    SSM Parameter Store에서 부팅 시 받아온다. user_data는 인스턴스 안에서 인증 없이 읽히는
#    메타데이터 엔드포인트로 노출되는데, 이 앱은 외부 API를 호출하므로 SSRF 표면이 실재한다.
#    AmazonSSMManagedInstanceCore는 /aws/ssm/* 경로만 허용하므로 앱 경로용 정책이 따로 필요하다.
#    kms:Decrypt는 SecureString을 푸는 데 필요하고, AWS 관리형 키(alias/aws/ssm)로 한정한다.
data "aws_iam_policy_document" "app_ec2_runtime" {
  statement {
    sid       = "ReadDeploymentArtifacts"
    actions   = ["s3:GetObject"]
    resources = ["arn:aws:s3:::${var.artifact_bucket_name}/*"]
  }

  statement {
    sid = "ReadAppSecrets"
    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParametersByPath",
    ]
    resources = ["arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter${var.ssm_parameter_path}/*"]
  }

  statement {
    sid       = "DecryptAppSecrets"
    actions   = ["kms:Decrypt"]
    resources = ["arn:aws:kms:${var.aws_region}:${data.aws_caller_identity.current.account_id}:alias/aws/ssm"]
  }
}

data "aws_caller_identity" "current" {}

resource "aws_iam_role_policy" "app_ec2_runtime" {
  name   = "${var.name_prefix}-app-ec2-runtime"
  role   = aws_iam_role.app_ec2.id
  policy = data.aws_iam_policy_document.app_ec2_runtime.json
}

resource "aws_iam_instance_profile" "app_ec2" {
  name = "${var.name_prefix}-app-ec2-profile"
  role = aws_iam_role.app_ec2.name
}
