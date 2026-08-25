# App EC2 전용 IAM 역할.
#
# loadtest의 관리형 정책 2개를 그대로 계승하고, 운영에만 필요한 커스텀 정책 1개를 더한다.
#  - CloudWatchAgentServerPolicy: CloudWatch Agent가 mem_used_percent를 게시할 수 있게 한다.
#    기본 CloudWatch 지표는 EC2 메모리를 노출하지 않는데, -Xmx768m이 2GB 박스에서 실제로
#    버티는지 보려면 이 지표가 필요하다.
#  - AmazonSSMManagedInstanceCore: Session Manager 접속과 RDS로의 포트포워딩에 필요하다.
#    AL2023 AMI는 SSM Agent가 이미 설치·실행 중이라 이 권한만 있으면 바로 동작한다.
#  - (커스텀) 배포 아티팩트 읽기 + 앱 시크릿 읽기: 아래 참고.
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

resource "aws_iam_role_policy_attachment" "app_ec2_cloudwatch_agent" {
  role       = aws_iam_role.app_ec2.name
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
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
