# CloudFront 서명용 키페어(terraform/)와는 완전히 별개의, EC2 SSH 접속 전용 키페어.
# 개인키는 로컬에서 별도로 생성해 var.ssh_public_key_path가 그 공개키(.pub)를 가리키게 한다
# (예: ssh-keygen -t ed25519 -f ./yourtrip-loadtest-ssh -C yourtrip-loadtest).
# 개인키 파일 자체는 terraform/loadtest/*.pem으로 저장해도 .gitignore에 걸린다.
resource "aws_key_pair" "ssh" {
  key_name   = "${var.name_prefix}-ssh"
  public_key = file(var.ssh_public_key_path)

  tags = {
    Name = "${var.name_prefix}-ssh"
  }
}
