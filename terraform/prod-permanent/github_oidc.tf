# GitHub Actions가 AWS에 붙는 경로(#120).
#
# 러너는 워크플로 실행마다 GitHub이 발급한 OIDC 토큰을 STS에 제시하고 1시간짜리 임시
# 자격증명을 받는다. 저장소에 저장되는 비밀은 0개다 — GitHub Secrets에 액세스 키를 두는
# 방식과 비교한 표는 docs/tasks/cd-pipeline/README.md에 있다.
#
# 왜 prod-permanent인가: 이 역할은 terraform/prod가 destroy된 동안에도 살아 있어야 한다.
# 운영 서버는 온디맨드라 대부분의 기간 내려가 있는데, 그때도 dev 머지마다 JAR을 S3에
# 올리는 것까지는 계속돼야 하기 때문이다. 역할을 prod에 두면 destroy 직후부터 AssumeRole
# 자체가 실패해 워크플로가 빨간불이 된다. 아티팩트 버킷과 수명이 같은 자리가 여기다.

data "aws_caller_identity" "current" {}

# 계정 하나에 같은 URL의 OIDC provider는 하나만 존재할 수 있다. 다른 프로젝트가 이미
# 만들어 뒀다면 생성이 EntityAlreadyExists로 실패하므로, 기존 ARN을 변수로 받아 재사용할
# 수 있게 갈랐다(terraform/prod/iam.tf가 ASG 서비스 연결 역할을 조건부로 만드는 것과 같은
# 취지 — "이 저장소를 clone해 자기 계정에 처음 apply하는 사람도 그대로 뜬다"를 지킨다).
#
# thumbprint_list는 넣지 않는다. AWS는 2023년부터 이 잘 알려진 IdP의 지문을 자체 신뢰
# 저장소로 검증하며 인자를 생략하면 알아서 채운다. 알려진 값을 코드에 박아두면 GitHub이
# 인증서를 갱신했을 때 낡은 값만 남는다.
resource "aws_iam_openid_connect_provider" "github" {
  count = var.github_oidc_provider_arn == "" ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

locals {
  github_oidc_provider_arn = (
    var.github_oidc_provider_arn != ""
    ? var.github_oidc_provider_arn
    : aws_iam_openid_connect_provider.github[0].arn
  )

  # ASG는 terraform/prod가 만들므로 여기서 ARN을 참조할 수 없다. 이름으로 재구성하는데,
  # 두 모듈의 name_prefix 기본값이 같다는 데 기대는 암묵적 결합이다. prod에서 name_prefix를
  # 바꾸면 여기도 함께 바꿔야 한다.
  # ARN 중간의 UUID 세그먼트는 ASG를 다시 만들 때마다 바뀌므로 그 자리만 와일드카드다.
  asg_arn_pattern = "arn:aws:autoscaling:${var.aws_region}:${data.aws_caller_identity.current.account_id}:autoScalingGroup:*:autoScalingGroupName/${var.name_prefix}-asg"

  artifact_key_parameter_arn = "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter${var.ssm_parameter_path}/artifact_key"
}

# 누가 이 역할을 맡을 수 있는가.
#
# sub를 dev 브랜치로 못박는다. 롤백용 workflow_dispatch도 sub가 '이벤트 종류'가 아니라
# '선택된 ref'로 결정되므로, dev에서 실행하는 한 같은 조건에 걸린다. 다른 브랜치를 허용할
# 이유도 없다 — 워크플로 파일은 기본 브랜치에 있어야 트리거되고 dispatch 버튼도 거기서만
# 나타나므로, dev 아닌 ref로 이 역할이 필요한 경우가 실제로 생기지 않는다.
#
# aud 조건을 반드시 함께 건다. sub만 걸고 aud를 빼는 것이 이 패턴에서 가장 흔한 구멍이다.
data "aws_iam_policy_document" "github_actions_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:ref:refs/heads/dev"]
    }
  }
}

resource "aws_iam_role" "github_actions" {
  name = "${var.name_prefix}-github-actions-role"
  # IAM의 description은 Latin-1( -~, ¡-ÿ)만 받는다. 한글을 넣으면
  # CreateRole이 ValidationError로 거부되므로 영어로 쓴다 — iam.tf의 서비스 연결 역할도
  # 같은 이유로 영어다. 한국어 설명은 이 파일 맨 위 주석에 있다.
  description        = "CD workflow only. Uploads artifacts, updates the deploy pointer, and triggers instance refresh."
  assume_role_policy = data.aws_iam_policy_document.github_actions_assume.json

  # 배포 한 번에 필요한 시간(빌드 제외)보다 넉넉하되 기본 최대치를 늘리지 않는다.
  max_session_duration = 3600
}

# 무엇을 할 수 있는가.
#
# 이 역할은 "배포"만 할 수 있고 "형상 변경"은 할 수 없다. 인프라의 모양은 terraform만
# 바꾼다는 규칙(CLAUDE.md)을 IAM으로 강제하는 것이다.
#
# 의도적으로 주지 않은 것:
#   - ssm:SendCommand      : AWS-RunShellScript는 운영 인스턴스의 root 원격 실행이다.
#                            배포 파이프라인이 이걸 가지면 워크플로 수정 권한이 곧 운영
#                            서버 셸 권한이 된다.
#   - ssm:GetParametersByPath : 있으면 /yourtrip/prod/env/* 의 시크릿을 통째로 조회할 수 있다.
#   - s3:GetObject         : 롤백 전 존재 확인에는 ListBucket이면 충분하다. 읽기까지 주면
#                            CD가 과거 배포본을 통째로 내려받을 수 있게 된다.
#   - s3:DeleteObject      : 아티팩트는 롤백의 근거다. 파이프라인이 지울 수 있으면 안 된다.
#   - autoscaling:UpdateAutoScalingGroup, ec2:* : 형상 변경.
data "aws_iam_policy_document" "github_actions_deploy" {
  statement {
    sid       = "PutDeploymentArtifact"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.artifacts.arn}/app/*"]
  }

  # 롤백할 SHA의 JAR이 실제로 있는지 먼저 확인하기 위한 것. 없는 키로 배포를 시작하면
  # 인스턴스가 부팅에 실패할 때까지 알 수 없다.
  statement {
    sid       = "ListDeploymentArtifacts"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.artifacts.arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["app/*"]
    }
  }

  # 파라미터 하나만. /yourtrip/prod/* 로 넓히면 CD가 DB_PASSWORD와 JWT_SECRET을 덮어쓸 수 있다.
  # GetParameter는 갱신 직전 값을 읽어 "롤백하려면 이 SHA로 돌아가라"를 요약에 남기는 데 쓴다.
  statement {
    sid       = "UpdateArtifactKeyParameter"
    actions   = ["ssm:PutParameter", "ssm:GetParameter"]
    resources = [local.artifact_key_parameter_arn]
  }

  # 취소 권한은 주지 않는다. 폴링이 타임아웃해도 워크플로는 refresh를 그대로 두고 job만
  # 실패시킨다 — 취소하면 ASG가 용량을 줄이면서 건강한 인스턴스를 종료할 수 있다는 것이
  # 실측으로 확인됐다(docs/tasks/cd-pipeline/verification.md). 사람이 콘솔에서 판단해
  # 취소해야 할 때는 운영자 자격증명을 쓴다.
  statement {
    sid       = "TriggerInstanceRefresh"
    actions   = ["autoscaling:StartInstanceRefresh"]
    resources = [local.asg_arn_pattern]
  }

  # autoscaling·elasticloadbalancing의 Describe* 는 리소스 수준 권한을 지원하지 않아
  # "*"일 수밖에 없다. 읽기 전용이라 수용한다.
  statement {
    sid = "ReadDeploymentState"
    actions = [
      "autoscaling:DescribeAutoScalingGroups",
      "autoscaling:DescribeInstanceRefreshes",
      "elasticloadbalancing:DescribeTargetHealth",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "github_actions_deploy" {
  name   = "${var.name_prefix}-github-actions-deploy"
  role   = aws_iam_role.github_actions.id
  policy = data.aws_iam_policy_document.github_actions_deploy.json
}
