# App EC2 전용 IAM 역할.
# - CloudWatchAgentServerPolicy: CloudWatch Agent가 mem_used_percent(메모리 사용률)를
#   게시할 수 있게 해준다. 기본 CloudWatch 지표는 EC2 메모리를 노출하지 않아서,
#   "t3.micro 1GB로 JVM+Tomcat 200스레드가 버티는가"를 실측으로 확인하려면 이 권한이 필수다.
# - AmazonSSMManagedInstanceCore: SSM Session Manager 포트포워딩(RDS 시딩용, 아래 참고)에
#   필요. AL2023 AMI는 SSM Agent가 이미 설치·실행 중이라 이 권한만 있으면 별도 설치 없이
#   바로 동작한다.
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

# SSM Session Manager로 App EC2를 경유해 RDS(사설 서브넷)로 포트포워딩하기 위한 권한.
# 목적: 시딩/직접 psql 검증 때마다 App EC2에 postgresql 클라이언트를 설치해두는 대신,
# 로컬 psql이 RDS로 직접 터널링되게 해서 "측정 대상 인스턴스는 최대한 순수하게 유지한다"는
# 이번 인프라의 설계 원칙(Prometheus/Grafana/Redis를 EC2 밖에 둔 것과 동일한 이유)을
# App EC2 자체에도 지킨다.
resource "aws_iam_role_policy_attachment" "app_ec2_ssm" {
  role       = aws_iam_role.app_ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "app_ec2" {
  name = "${var.name_prefix}-app-ec2-profile"
  role = aws_iam_role.app_ec2.name
}
