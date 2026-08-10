#!/bin/bash
# k6 EC2 부트스트랩 — Amazon Linux 2023
set -euxo pipefail

dnf install -y git

# k6 공식 설치 방법(현재 grafana.com/docs/k6 기준) — repo.rpm 패키지가 실제 저장소 설정을
# 등록해준다. 수동으로 baseurl=https://dl.k6.io/rpm을 직접 가리키는 .repo 파일을 작성하는
# 방식은 해당 경로의 repodata가 404라 실패하는 것을 실측으로 확인해 이 방식으로 교체했다.
dnf install -y https://dl.k6.io/rpm/repo.rpm
dnf install -y k6

# k6 부하 스크립트(scripts/k6/detail-ramping.js 등)만 필요하므로 shallow clone.
# app_git_ref는 브랜치명이 아니라 커밋 해시일 수 있다. `git clone --branch --depth 1`은
# 브랜치/태그 이름만 지원하고 임의 커밋 SHA는 실패한다(App EC2에서 실측으로 확인된 버그).
# init+remote+fetch by SHA는 브랜치명/SHA 둘 다 지원한다.
mkdir -p /opt/app
cd /opt/app
git init -q
git remote add origin "${app_repo_url}"
git fetch --depth 1 origin "${app_git_ref}"
git checkout -q FETCH_HEAD
