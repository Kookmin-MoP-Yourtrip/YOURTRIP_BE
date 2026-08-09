resource "aws_instance" "k6" {
  ami                         = data.aws_ami.al2023.id
  instance_type               = var.k6_instance_type
  subnet_id                   = aws_subnet.primary.id
  vpc_security_group_ids      = [aws_security_group.k6.id]
  key_name                    = aws_key_pair.ssh.key_name
  monitoring                  = var.enable_detailed_monitoring
  associate_public_ip_address = true

  user_data = templatefile("${path.module}/templates/k6-user-data.sh.tpl", {
    app_repo_url = var.app_repo_url
    app_git_ref  = var.app_git_ref
  })

  tags = {
    Name = "${var.name_prefix}-k6"
  }
}
