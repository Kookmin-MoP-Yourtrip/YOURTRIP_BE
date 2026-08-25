# CloudFront 서명용 키페어(terraform/)와는 완전히 별개의, EC2 SSH 접속 전용 키페어.
# 개인키는 로컬에서 별도로 생성하고 var.ssh_public_key_path가 그 공개키(.pub)를 가리키게 한다:
#   ssh-keygen -t ed25519 -f ./yourtrip-prod-ssh -C yourtrip-prod
#
# 개인키가 tfstate에 들어가지 않게 하려는 것이 이 구조의 목적이다(CloudFront 키페어와 동일한
# 원칙). 개인키·공개키 모두 .gitignore 대상이지만, .pub은 아래 file()이 plan/apply 때마다
# 읽으므로 없으면 plan 자체가 실패한다 — 그래서 .worktreeinclude 복사 목록에 포함돼 있다.
#
# 평상시 인스턴스 접속은 SSM Session Manager를 쓴다. 이 키는 SSM이 동작하지 않을 때의
# 탈출구이며, 그래서 SG의 22번 포트도 개발자 IP로만 열려 있다.
resource "aws_key_pair" "ssh" {
  key_name   = "${var.name_prefix}-ssh"
  public_key = file(var.ssh_public_key_path)

  tags = {
    Name = "${var.name_prefix}-ssh"
  }
}
