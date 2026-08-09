# App EC2 전용 IAM 역할 — CloudWatch Agent가 mem_used_percent(메모리 사용률)를
# 게시할 수 있게 해준다. 기본 CloudWatch 지표는 EC2 메모리를 노출하지 않아서,
# "t3.micro 1GB로 JVM+Tomcat 200스레드가 버티는가"를 실측으로 확인하려면 이 권한이 필수다.
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

resource "aws_iam_instance_profile" "app_ec2" {
  name = "${var.name_prefix}-app-ec2-profile"
  role = aws_iam_role.app_ec2.name
}
