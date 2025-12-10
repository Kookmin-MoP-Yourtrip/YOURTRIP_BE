<h1>✈️ YOURTRIP — AI 기반 여행 코스 플래너</h1>



**개발 기간**: 2025.09 ~ 2025.12 (2025-2 모바일 프로그래밍 팀 프로젝트)

🗺️ **너의 여행은 (YOURTRIP)**  
AI 추천과 코스 공유 기능으로 여행 계획의 번거로움을 줄여주는 **여행 코스 생성 & 공유 앱**

> 정보 과다, 일정 조율, 목적지 탐색 피로도를 한 번에 줄여주는  
> **“여행 코스 플래너 + 여행 코스 SNS + AI 추천”** 서비스


<p align="center">
  <!-- <img src="이미지_URL_여기에" alt="YOURTRIP 메인 화면" width="900"/> -->
  <!-- <img width="6402" height="5083" alt="yourtrip_main" src="https://github.com/user-attachments/assets/2e1b3319-8806-4a51-b8a9-9fbdb7f5edcf" /> -->
  <img width="900" height="730" alt="yourtrip_main" src="https://github.com/user-attachments/assets/2e1b3319-8806-4a51-b8a9-9fbdb7f5edcf" />

</p>


---

## 📦 레포지토리

- 🎨 **Android App (FE)**: https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_FE  
- 🛠 **Spring Boot (BE)**: https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE  

---

## 👥 팀 소개

| 이름      | 역할              | GitHub                                           | 주요 담당 |
|--------  |--------------      |--------------------------------------------------|----------|
| 김태환   | Leader / BE / FE   | [@KimTaeHwan21](https://github.com/KimTaeHwan21) | 서버 구축 및 에러 수정, 기본 회원가입/로그인/비밀번호 변경 , 마이페이지, 인증/인가, 프론트 마이페이지 UI / 프로필 편집 |
| 남지은   | BE /FE             | [@zie-ning](https://github.com/zie-ning)         | 여행 코스 생성 및 일차별 일정 관리(BE), 코스 업로드 및 fork 로직(BE), AI 코스 생성 플로우 UI(FE) |
| 최서구   | BE / FE            | [@choiseogu](https://github.com/choiseogu)       | 여행 피드 CRUD API 개발(BE), 피드에 대한 댓글 CRUD API 개발(BE), 나의 업로드 코스 및 피드 조회 플로우 기능 개발(FE) |
| 이다은   | FE                 | [@dani0910](https://github.com/dani0910)         | 회원가입/로그인/비밀번호 찾기, 스플래시, 나의 코스 생성/편집/업로드 전체 플로우 개발, 로그아웃, 네트워크/모델 구조 정의, FE 전반적인 UI와 기능 개선|
| 조혜원   | FE                 | [@agunggung22](https://github.com/agunggung22)   |UI/UX 설계, **홈/피드** 전체 화면 개발, 공통 View 컴포넌트 개발 및 프론트 구조 설계 |

---
 
## ✨ 프로젝트 소개

YOURTRIP은 사용자가 여행을 계획할 때 겪는

- 📚 **정보 과다**: 블로그, 인스타, 유튜브 등 흩어진 정보
- 🧩 **일정 조율의 어려움**: 친구와 날짜·코스를 맞추기 힘든 문제
- 🔍 **목적지 탐색 피로도**: 어디를 가야 할지 고르기만 하다 시간 보내는 문제

를 해결하기 위해 만들어진 **AI 기반 여행 코스 플래너**입니다.

- AI가 조건에 맞는 **추천 코스**를 생성해주고
- 사용자는 직접 **여행 코스를 만들고 관리**할 수 있으며
- 다른 사람이 만든 코스를 **공유 / 검색 / fork**해서
- 나만의 여행 계획으로 **커스터마이징**할 수 있습니다.
---

## 📌 주요 기능

### 1️⃣ 코스 탐색 & 검색

- 지역, 테마, 기간 등으로 분류된 **여행 코스 리스트**
- 해시태그 / 필터 기반으로 **내 취향에 맞는 코스** 빠르게 찾기
- 인기순 / 최신순 정렬 기능

### 2️⃣ 나의 여행 코스 관리

- 여행 일자/도시/동선 기반으로 **코스 생성 & 편집**
- 하루 단위 Day 별 일정 구성

### 3️⃣ AI 기반 코스 추천

- 여행 기간, 동행 인원, 선호 스타일 등 간단 정보만 입력하면  
  → 조건에 맞는 **AI 추천 코스** 자동 생성  
- 추천된 코스를 기반으로 세부 일정만 수정해서 사용

### 4️⃣ 코스 공유 & Fork

- 마음에 드는 코스를 **fork**해서 내 일정에 맞게 수정
- 여행이 끝난 후, 실제 다녀온 코스를 기반으로 **후기/수정** 가능

### 5️⃣ 피드(Feed) 업로드 & 소셜 기능

- 여행 사진, 위치, 설명을 담은 피드 게시
- 다른 사용자의 피드 보기
- 좋아요 및 댓글 작성 

### 6️⃣ 마이페이지 & 계정 관리

- 프로필 이미지/닉네임/비밀번호 변경
- 내가 만든 코스 / fork한 코스 / 저장한 코스 모아보기
- 회원 탈퇴, 로그아웃 등 계정 관련 기능

---

## 🌟 서비스 포인트

1️⃣ **“검색 지옥”에서 벗어나기**  
- 블로그, 카페, 유튜브를 끝없이 뒤지는 대신  
  → AI가 조건에 맞는 코스를 먼저 제안  
  → 마음에 들면 그대로 사용, 아니면 fork해서 내 스타일로 수정

2️⃣ **여행 코스를 “콘텐츠”로 공유**  
- 코스를 단순 일정이 아닌 **콘텐츠처럼 공유**  
- 잘 만든 코스를 다른 사람이 복사해서 쓰는 구조로  
  → 여행 계획이 쌓일수록 플랫폼 가치 상승

3️⃣ **모바일 환경 최적화**  
- 실제 여행 계획/조율이 가장 많이 일어나는 환경인 **모바일(Android)** 기준으로 UX 설계

---

## 🖥️ 화면 구성


| 스플래시 & 로그인 | 코스 탐색 (홈) | 코스 상세 |
|:---------------:|:---------:|:---------:|
| <img width="480" height="481" alt="로그인" src="https://github.com/user-attachments/assets/e74ac42f-0c12-41f3-8748-b558a36d6698" /> | <img width="480" height="481" alt="홈" src="https://github.com/user-attachments/assets/62184bec-2c28-459e-a1e0-fb70f552f668" /> | <img width="240" height="481" alt="코스 상세" src="https://github.com/user-attachments/assets/1730a0fd-1b45-42a1-ad60-c7dd69ec506b" /> |

| 나의 코스  | 코스 편집 | 피드 | 마이페이지 |
|:---------:|:---------:|:----------:|:---:|
| <img width="240" height="481" alt="나의 코스 리스트" src="https://github.com/user-attachments/assets/ca7c8c5b-0ada-42c3-865e-e5cfb6d4473d" /> | <img width="240" height="481" alt="코스 생성" src="https://github.com/user-attachments/assets/caf8ec87-04ef-4ac1-821e-ea8d39017889" /> | <img width="240" height="481" alt="피드" src="https://github.com/user-attachments/assets/8a8cbe2a-f8a1-40ca-8a31-71c2a43680ad" /> | <img width="240" height="481" alt="마이 페이지" src="https://github.com/user-attachments/assets/5870e89e-9eb6-4834-b017-c2593dc89c47" /> |






---

## 🛠 기술 스택

### 🔧 Backend (Spring Boot)

- **Language**: Java
- **Framework**: Spring Boot, Spring MVC
- **Security**: Spring Security, JWT 기반 인증/인가
- **DB**: PostgreSQL, Spring Data JPA
- **Infra**: AWS EC2, RDS, S3
- **기타**: Nginx, Docker, Gradle

---

## 📂 프로젝트 구조

### 🛠 BE – Spring Boot

```bash
YOURTRIP_BE/
├── src/main/java/yourtrip
│   ├── domain/                       # 주요 도메인 계층
│   │    ├── feed/                    # 피드 및 코스 공유 관련 도메인
│   │    ├── mycourse/                # 내가 만든 코스 관리 도메인
│   │    ├── mypage/                  # 마이페이지 (프로필, 계정 설정)
│   │    ├── uploadcourse/            # 업로드된 코스 정보 저장 및 관리
│   │    ├── user/                    # 회원 도메인
│   │
│   ├── global/                       # 공통 컴포넌트 모음
│   │    ├── common/                  # 공통 응답, 유틸
│   │    ├── config/                  # CORS, Swagger, Security 등 설정 파일
│   │    ├── converter/               # Enum/String 변환기
│   │    ├── exception/               # 전역 예외 처리 및 핸들러
│   │    ├── gemini/                  # Gemini API 연동 서비스
│   │    ├── jwt/                     # JWT 발급 및 인증 필터
│   │    ├── kakao/                   # Kakao OAuth 인증 로직
│   │    ├── mail/                    # 이메일 인증(메일 발송)
│   │    ├── s3/                      # AWS S3 파일 업로드 및 다운로드 모듈
│   │    ├── security/                # Security Config
└── build.gradle
```
### ⚙️ BE - application.yml
```yml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      ddl-auto: ${DB_DDL_AUTO}
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.PostgreSQLDialect
    show-sql: true

  mail:
    host: smtp.gmail.com
    port: 587
    username: ${MAIL_EMAIL}
    password: ${MAIL_PASSWORD}
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true

  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB

jwt:
  secret: ${JWT_SECRET}

logging:
  level:
    org.hibernate.SQL: debug
    org.springdoc: debug
    org.springframework.web: info
    org.springframework.security: DEBUG

springdoc:
  api-docs:
    enabled: true
  swagger-ui:
    enabled: true
    path: /swagger-ui.html
    operationsSorter: method
    displayRequestDuration: true
    persistAuthorization: true
  override-with-generic-response: false

kakao:
  auth:
    client-id: ${KAKAO_CLIENT_ID}
    client-secret: ${KAKAO_CLIENT_SECRET}
    redirect-uri: http://localhost:8080/api/users/login/kakao/callback
  api-key: ${KAKAO_API_KEY}
  local:
    base-url: "https://dapi.kakao.com"

server:
  forward-headers-strategy: framework
  port: 8080
  address: 0.0.0.0
  ssl:
    enabled: false
    # key-store: ${KEY_STORE}
    # key-store-type: ${KEY_TYPE}
    # key-store-password: ${KEY_PASS}

s3:
  bucket: ${S3_BUCKET}
  region: ap-northeast-2
  allowed-content-types: image/png,image/jpeg,image/webp,image/jpg,video/mp4,video/quicktime,video/webm
  max-size-bytes: 10485760 # 10MB

  access-key: ${AWS_ACCESS_KEY}
  secret-key: ${AWS_SECRET_KEY}

gemini:
  api-key: ${GEMINI_API_KEY}
```

## 🚀 실행 방법

### 🛠 BE – Spring Boot

```bash
git clone https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE.git
cd YOURTRIP_BE
```
- 환경 변수 설정 후 실행
  ```bash
  ./gradlew bootRun
  ```
- 실행 참고 사항
  ```bash
  1. Java 21(Amazon Corretto) 설치 필요
  2. 환경변수 설정 필수 (.env 또는 OS 환경변수)
   - DB_URL, DB_USERNAME, DB_PASSWORD
   - JWT_SECRET
   - MAIL_EMAIL, MAIL_PASSWORD
   - AWS_ACCESS_KEY, AWS_SECRET_KEY
   - KAKAO_CLIENT_ID, KAKAO_SECRET
   - S3_BUCKET
   - GEMINI_API_KEY etc.
  3. EC2에서는 8080 포트 사용, Swagger는 /swagger-ui.html
  ```

## 📎 기타 자료

#### 🎥 시연 영상: (https://drive.google.com/file/d/1MirNvxI5y35qS9tPtCIv9aWd2Gv4REeS/view)

#### 📑 발표 자료(PPT): 업데이트 예정

#### 🎥 노션 링크: (https://aquamarine-book-1e6.notion.site/2025-2-_-2-_1-26b77c61398180168bcfd3eee08b8e0c?source=copy_link)

#### 🎥 피그마 링크: (https://www.figma.com/design/YcCdV6Eqf486kKcZOK6mUm)

#### 🎥 피그잼 링크: (https://www.figma.com/board/TjRf47J8qvnVRsg2dXcSy9/)

#### 🎥 Swagger 링크: (https://yourtrip.site/swagger-ui/index.html)

#### 🎥 ERD 링크: (https://www.erdcloud.com/d/FvCG4hazXKR4vL8aq)
