data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }

  filter {
    name   = "architecture"
    values = ["x86_64"]
  }
}

# most_recent = true가 loadtest에서는 함정이었다 — plan을 돌릴 때마다 AMI가 갱신돼 인스턴스
# 교체가 떴다. ASG에서는 그 문제가 완화된다: AMI는 plan 시점에 해석돼 Launch Template 버전에
# 박히고 ASG가 재해석하지 않으므로, 한 apply 안의 fleet은 항상 같은 이미지를 쓴다.
#
# 남는 문제는 "같은 커밋을 다른 날 apply하면 다른 이미지가 나온다"는 재현성이다. 그래서
# 기본은 최신을 쓰되 var.app_ami_id로 핀할 수 있게 하고, 매 apply가 자신이 쓴 AMI를
# output으로 알려주게 한다(outputs.tf의 app_ami_id).
locals {
  app_ami_id = var.app_ami_id != "" ? var.app_ami_id : data.aws_ami.al2023.id

  # instance refresh 조건의 정본. CD 워크플로가 aws CLI에 --preferences file:// 로 넘기는
  # 파일과 같은 것을 여기서도 읽는다. 두 경로가 각자 숫자를 들고 있으면 "terraform으로
  # 굴리면 무중단인데 CD로 굴리면 아니다"가 되는데, 그 차이는 배포가 실제로 터지기
  # 전까지 드러나지 않는다. deploy/prod/의 systemd 유닛·JVM 옵션을 file()로 잇는 것과
  # 같은 방식이다.
  refresh_prefs = jsondecode(file("${path.module}/../../deploy/prod/instance-refresh-preferences.json"))
}

# 다음에 뜨는 인스턴스가 내려받을 JAR 키. terraform이 만들지도 관리하지도 않는 값이지만
# (시크릿과 같은 이유로 CLI 1회 등록한다) 여기서 읽는 데는 목적이 있다 — 등록돼 있지 않으면
# plan이 그 자리에서 실패한다. 이 블록이 없으면 인스턴스는 정상적으로 뜨고 user-data만
# 조용히 실패해, 헬스체크가 계속 깨지는 이유를 찾아 로그를 뒤져야 한다.
data "aws_ssm_parameter" "artifact_key" {
  name = "${var.ssm_parameter_path}/artifact_key"
}

# Grafana Cloud 접속 정보가 등록돼 있지 않으면 plan이 여기서 실패한다. 위 artifact_key와
# 정확히 같은 이유다 — 등록을 빠뜨려도 인스턴스는 정상적으로 뜨고 관측만 조용히 비어,
# 대시보드가 왜 빈지 찾아 user-data 로그를 뒤져야 한다.
#
# 다섯 개 중 URL 하나만 읽는다. aws_ssm_parameter data 소스가 읽은 값은 tfstate에 남으므로,
# 토큰을 읽으면 시크릿을 SSM으로 옮긴 의미가 사라진다. 비밀이 아닌 값으로 존재만 확인한다.
data "aws_ssm_parameter" "grafana_cloud_prometheus_url" {
  name = "${var.ssm_parameter_path}/grafana/prometheus_url"
}

resource "aws_launch_template" "app" {
  name_prefix   = "${var.name_prefix}-app-"
  image_id      = local.app_ami_id
  instance_type = var.app_instance_type
  key_name      = aws_key_pair.ssh.key_name

  iam_instance_profile {
    name = aws_iam_instance_profile.app_ec2.name
  }

  network_interfaces {
    associate_public_ip_address = true
    security_groups             = [aws_security_group.app.id]
    delete_on_termination       = true
  }

  # IMDSv2 강제. 시크릿을 user_data에서 SSM으로 옮긴 조치와 짝을 이룬다 — 앱이 Kakao·Gemini로
  # 아웃바운드 HTTP를 하므로 SSRF로 메타데이터 엔드포인트를 찔릴 표면이 실재하는데,
  # IMDSv2는 PUT으로 토큰을 먼저 받아야 하므로 단순 GET 기반 SSRF로는 뚫리지 않는다.
  # hop_limit = 1은 컨테이너 등 한 홉 건너에서의 접근을 막는다.
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  monitoring {
    enabled = var.enable_detailed_monitoring
  }

  # t3 기본값이지만 명시한다 — 계정 기본 설정이 standard로 뒤집혀 있으면 스케일아웃으로 뜬
  # 인스턴스가 launch credit만 갖고 시작해 곧바로 스로틀된다. 부하테스트에서 "정지 후 재기동 시
  # CPUCreditBalance가 2분 만에 0"이 실측됐다(docs/tasks/jvm-heap-sizing/ab-measurement.md).
  # 대가로 크레딧을 초과해 쓰면 vCPU-시간당 과금이 붙는다.
  credit_specification {
    cpu_credits = "unlimited"
  }

  block_device_mappings {
    device_name = "/dev/xvda"

    ebs {
      volume_size           = var.app_root_volume_size
      volume_type           = "gp3"
      delete_on_termination = true
      encrypted             = true
    }
  }

  user_data = base64encode(templatefile("${path.module}/templates/app-user-data.sh.tpl", {
    # 비밀이 아닌 값만 여기로 넘어간다. 비밀은 인스턴스가 SSM에서 직접 받아간다.
    db_host     = aws_db_instance.this.address
    db_name     = var.rds_db_name
    db_username = var.rds_username
    db_ddl_auto = var.db_ddl_auto
    redis_host  = aws_elasticache_cluster.this.cache_nodes[0].address
    redis_port  = 6379

    s3_bucket                  = var.s3_bucket
    cloudfront_domain          = var.cloudfront_domain
    cloudfront_key_pair_id     = var.cloudfront_key_pair_id
    cloudfront_distribution_id = var.cloudfront_distribution_id

    ssm_path        = var.ssm_parameter_path
    artifact_bucket = var.artifact_bucket_name

    # systemd 유닛과 JVM 옵션은 다시 타이핑하지 않고 deploy/prod/의 정본을 읽는다.
    # templatefile()은 주입된 값을 재스캔하지 않으므로, 유닛 안의 $JVM_OPTS가 terraform
    # 치환 대상이 되지 않는다 — loadtest 템플릿이 경고하던 함정을 구조적으로 피한다.
    # .gitattributes의 `deploy/** text eol=lf`가 CRLF 유입을 막아준다(없으면 유닛이 조용히 깨진다).
    service_unit = file("${path.module}/../../deploy/prod/yourtrip-app.service")
    jvm_opts_env = file("${path.module}/../../deploy/prod/jvm-opts.env")

    # Alloy 설정도 같은 방식으로 잇는다 — 값과 근거를 저장소가 들고 있어야 하고,
    # .gitattributes의 `deploy/** text eol=lf`가 CRLF 유입을 막아준다.
    #
    # ⚠️ config.alloy에 달러나 퍼센트 뒤에 중괄호가 오는 표기가 있으면 templatefile()이
    #    보간으로 해석해 이 apply가 그 자리에서 깨진다(주석 안이라도 마찬가지다).
    #    확인 명령은 deploy/prod/README.md에 있다.
    alloy_config  = file("${path.module}/../../deploy/prod/config.alloy")
    alloy_version = var.alloy_version
  }))

  # ASG가 붙이는 태그와 별개로, 인스턴스·볼륨에 직접 붙는 태그다.
  tag_specifications {
    resource_type = "instance"

    tags = {
      Name = "${var.name_prefix}-app"
    }
  }

  tag_specifications {
    resource_type = "volume"

    tags = {
      Name = "${var.name_prefix}-app-volume"
    }
  }

  tags = {
    Name = "${var.name_prefix}-app-lt"
  }
}

resource "aws_autoscaling_group" "app" {
  name = "${var.name_prefix}-asg"

  # 서비스 연결 역할이 먼저 있어야 ALB 타깃 그룹 검증이 통과한다. 역할이 이미 있는 계정에서는
  # iam.tf의 리소스가 count = 0이라 이 depends_on은 빈 리스트를 가리키며 아무 제약도 걸지 않는다.
  depends_on = [aws_iam_service_linked_role.autoscaling]

  # 앱 인스턴스는 primary 한 AZ에만 둔다 — RDS·ElastiCache와 같은 AZ여야 하기 때문이다.
  # ALB는 두 AZ에 걸쳐 있지만 타깃은 한 AZ에 모인다. AZ 장애에 그대로 노출되는 구조이며,
  # 지연·실측 재현성을 택한 결과로 설계 문서에 한계로 적혀 있다.
  vpc_zone_identifier = [aws_subnet.primary.id]

  min_size         = var.asg_min_size
  max_size         = var.asg_max_size
  desired_capacity = var.asg_desired_capacity

  target_group_arns = [aws_lb_target_group.app.arn]

  # EC2(프로세스 생존)가 아니라 ELB(요청 처리 가능 여부)로 판정한다. JVM이 살아 있어도
  # 앱이 요청을 못 받는 상태를 잡아내야 하기 때문이다.
  health_check_type         = "ELB"
  health_check_grace_period = var.asg_health_check_grace_period

  # 새 인스턴스가 지표에 반영되기까지의 유예. JIT 예열 중인 인스턴스의 낮은 처리량이
  # Target Tracking 지표를 오염시켜 추가 스케일아웃을 부르는 것을 막는다.
  default_instance_warmup = var.asg_instance_warmup

  launch_template {
    id = aws_launch_template.app.id
    # $Latest가 아니라 구체적 버전을 박는다 — $Latest는 ASG가 언제 새 버전을 집어드는지
    # 예측할 수 없게 만든다. 아래 instance_refresh가 교체 시점을 명시적으로 관리한다.
    version = aws_launch_template.app.latest_version
  }

  # Launch Template이 바뀌면(AMI 핀 변경 등) 실행 중인 인스턴스를 굴려 교체한다.
  # min_healthy 100 / max_healthy 200이라 '먼저 띄우고 나중에 죽이는' 순서가 되어
  # desired=1에서도 무중단으로 교체된다 — max_size 2가 이걸 가능하게 하는 여유 슬롯이다.
  #
  # ⚠️ 이 블록은 terraform이 LT 변경을 감지해 refresh를 트리거할 때만 쓰인다. 배포는 더 이상
  # LT를 바꾸지 않으므로(JAR 키가 SSM으로 빠졌다, #120) CD는 start-instance-refresh를 CLI로
  # 직접 부르는데, 그 경로에는 이 블록이 적용되지 않고 AWS 기본값이 쓰인다. 기본값은
  # MinHealthyPercentage 90이고, desired=1에서 그 값은 '먼저 죽이고 나중에 띄우는' 순서를
  # 허용해 무중단이 깨진다. 그래서 양쪽이 local.refresh_prefs의 같은 파일을 읽는다.
  instance_refresh {
    strategy = "Rolling"

    preferences {
      min_healthy_percentage = local.refresh_prefs.MinHealthyPercentage
      max_healthy_percentage = local.refresh_prefs.MaxHealthyPercentage

      # ⚠️ 기본값(false)과 같지만 반드시 명시한다. true면 ASG는 '이미 목표 LT 버전으로 도는'
      # 인스턴스를 건너뛰는데, 이 구성에서 LT는 배포마다 바뀌지 않으므로 fleet 전체가 skip
      # 대상이 된다 — refresh가 아무것도 교체하지 않고 즉시 Successful이 되어, 배포는
      # 초록불인데 옛 JAR이 계속 도는 상태가 된다. LT를 불변으로 만든 대가로 생긴 함정이라
      # 값이 눈에 보이는 편이 낫다.
      skip_matching = local.refresh_prefs.SkipMatching
    }

    # triggers에 "launch_template"을 넣지 않는다 — Launch Template 변경은 원래 항상 refresh를
    # 트리거하므로 중복이고, terraform validate가 경고한다. 다른 속성(예: 태그)까지 트리거로
    # 삼고 싶을 때만 이 인자를 쓴다.
  }

  metrics_granularity = "1Minute"
  enabled_metrics = [
    "GroupMinSize",
    "GroupMaxSize",
    "GroupDesiredCapacity",
    "GroupInServiceInstances",
    "GroupPendingInstances",
    "GroupTerminatingInstances",
    "GroupTotalInstances",
  ]

  # provider의 default_tags는 ASG에 자동 전파되지 않는다(ASG는 tag 블록이 별도다).
  # 콘솔에서 이 그룹이 무엇인지, 그리고 destroy 대상인지 구분할 수 있어야 하므로 명시한다.
  tag {
    key                 = "Name"
    value               = "${var.name_prefix}-asg"
    propagate_at_launch = false
  }

  tag {
    key                 = "Project"
    value               = "yourtrip"
    propagate_at_launch = true
  }

  tag {
    key                 = "Component"
    value               = "prod"
    propagate_at_launch = true
  }

  tag {
    key                 = "ManagedBy"
    value               = "terraform"
    propagate_at_launch = true
  }

  tag {
    key                 = "Ephemeral"
    value               = "true"
    propagate_at_launch = true
  }
}

# 요청 수 기반 Target Tracking.
#
# CPU 기반을 쓰지 않는 이유가 실측에 있다: maxThreads=32 구성에서 process_cpu_usage는 실제
# 여유를 과대평가한다. T200(0.940)이 T32(0.810)보다 사용률이 높은데 처리량은 오히려 낮았고,
# 사용률 하락의 절반은 "일을 덜 해서"가 아니라 "워커가 32개뿐이라 I/O 대기 중 CPU를 다 못
# 채워서"였다(docs/tasks/tomcat-thread-sizing/cpu-cost-decomposition.md).
#
# ⚠️ ALBRequestCountPerTarget은 60초 SUM이라 target_value가 '분당' 값이다.
#    초당 값을 넣으면 임계값이 60배 낮아져 상시 스케일아웃이 된다.
resource "aws_autoscaling_policy" "request_count" {
  name                   = "${var.name_prefix}-scaleout-request-count"
  autoscaling_group_name = aws_autoscaling_group.app.name
  policy_type            = "TargetTrackingScaling"

  target_tracking_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      # ALB와 타깃 그룹의 arn_suffix를 슬래시로 이은 형식이어야 한다(전체 ARN이 아니다).
      resource_label = "${aws_lb.this.arn_suffix}/${aws_lb_target_group.app.arn_suffix}"
    }

    target_value = var.scaleout_request_count_per_target_per_minute
  }
}
