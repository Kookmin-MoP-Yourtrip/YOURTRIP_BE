<h1>✈️ YOURTRIP — AI 기반 여행 코스 플래너 (Backend)</h1>



**개발 기간**: 2025.09 ~ 2025.12 (2025-2 모바일 프로그래밍 팀 프로젝트)

🗺️ **너의 여행은 (YOURTRIP)**  
AI 추천과 코스 공유 기능으로 여행 계획의 번거로움을 줄여주는 **여행 코스 생성 & 공유 앱**

> 정보 과다, 일정 조율, 목적지 탐색 피로도를 한 번에 줄여주는  
> **“여행 코스 플래너 + 여행 코스 SNS + AI 추천”** 서비스

이 저장소는 그중 **Spring Boot 백엔드**를 담당한다. 팀 구성·화면 구성 등 FE를 포함한 전체 프로젝트 소개는 모노레포 최상위 README를 참고한다.

<p align="center">
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
- 인기순 / 최신순 정렬 기능 (Redis 캐싱 기반 조회수·랭킹)

### 2️⃣ 나의 여행 코스 관리

- 여행 일자/도시/동선 기반으로 **코스 생성 & 편집**
- 하루 단위 Day 별 일정 구성

### 3️⃣ AI 기반 코스 추천

- 여행 기간, 동행 인원, 선호 스타일 등 간단 정보만 입력하면
  → 조건에 맞는 **AI 추천 코스** 자동 생성(Gemini + Kakao 장소 검색 보정)
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
- 이메일 회원가입/로그인/로그아웃 등 계정 관련 기능

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

## 🛠 기술 스택 (Backend)

- **Language**: Java 21
- **Framework**: Spring Boot 3.5.7, Spring MVC, Spring WebFlux(WebClient 용도 — 리액티브 컨트롤러 아님)
- **Build**: Gradle 8.14.3 (wrapper 포함)
- **Security**: Spring Security + JWT(`io.jsonwebtoken:jjwt` 0.11.5) 기반 인증/인가
- **DB**: PostgreSQL + Spring Data JPA(Hibernate) — 테스트만 H2 인메모리(PostgreSQL 호환 모드)
- **캐시**: Redis(Spring Data Redis + Lettuce) — 인기 코스 목록/상세 캐싱, 조회수 카운터, 랭킹 갱신 분산 락
- **모니터링**: Spring Boot Actuator + Micrometer Prometheus, 로컬은 docker-compose의 Prometheus/Grafana로 관측
- **AI**: Gemini(`com.google.genai:google-genai` 1.28.0) — 코스 추천 생성
- **외부 연동**: Kakao(장소 검색 전용, 로그인은 미사용), AWS SDK v2(S3 + CloudFront Signed URL), Spring Mail
- **문서화**: springdoc-openapi 2.6.0 (Swagger UI: `/swagger-ui.html`)
- **Infra**: AWS EC2, RDS(PostgreSQL), S3, CloudFront, Nginx, Docker

---

## 📂 프로젝트 구조

### 🛠 BE – Spring Boot

```bash
YOURTRIP_BE/
├── build.gradle
├── docker-compose.yml            # 로컬 Redis + Prometheus + Grafana
└── src/main/java/backend/yourtrip/
    ├── domain/
    │   ├── feed/                 # 피드(여행 사진/후기) CRUD, 댓글, 좋아요, 해시태그
    │   ├── mycourse/              # 내가 만든 코스(day별 일정, 장소) CRUD
    │   ├── mypage/                # 마이페이지 — user 도메인 entity/repository 재사용
    │   ├── uploadcourse/          # 업로드(공개)된 코스, fork/좋아요/조회수 등
    │   └── user/                  # 이메일 회원가입/로그인/JWT 발급
    └── global/
        ├── cloudfront/            # CloudFront Signed URL 발급·무효화
        ├── config/                # SecurityConfig, SwaggerConfig, WebConfig, RedisConfig 등
        ├── exception/             # BusinessException, GlobalExceptionHandler
        ├── gemini/                # Gemini AI 코스 추천 연동
        ├── jwt/                   # JwtAuthenticationFilter, JwtTokenProvider
        ├── kakao/                 # 카카오 지도/장소 검색 클라이언트(AI 코스 생성 시 장소 보정용)
        ├── mail/                  # 이메일 인증 발송
        └── s3/                    # AWS S3 업로드/다운로드
```

**표준 레이어링 패턴**: `controller` → `service`(인터페이스 + `*ServiceImpl`) → `repository`(Spring Data JPA) → `entity`, 그 옆에 `dto/request`·`dto/response`, `mapper`(entity↔DTO 변환).

### ⚙️ 설정 파일 구성

설정은 **공통(`application.yml`) + 프로필별 파일**로 나뉜다. 프로필은 `local`(기본, 로컬 개발) / `prod`(배포) / `test`(테스트, H2 인메모리 DB)이며, 필요한 환경변수 전체 목록은 [.env.example](.env.example)이 정본이다.

---

## 🚀 실행 방법

### 🛠 BE – Spring Boot

```bash
git clone https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE.git
cd YOURTRIP_BE
docker compose up -d redis
./gradlew bootRun
```

- 실행 참고 사항
  ```bash
  1. Java 21 필요 (build.gradle의 toolchain 지정)
  2. 환경변수 설정 필수 — .env.example을 복사해 .env로 저장하면 spring-dotenv가 자동으로 읽는다.
   - DB_URL, DB_USERNAME, DB_PASSWORD, DB_DDL_AUTO
   - JWT_SECRET
   - MAIL_EMAIL, MAIL_PASSWORD
   - KAKAO_API_KEY (장소 검색 전용, 로그인 미사용)
   - S3_BUCKET, AWS_ACCESS_KEY, AWS_SECRET_KEY
   - CLOUDFRONT_DOMAIN, CLOUDFRONT_KEY_PAIR_ID, CLOUDFRONT_PRIVATE_KEY_PATH, CLOUDFRONT_DISTRIBUTION_ID
   - GEMINI_API_KEY
   - REDIS_HOST, REDIS_PORT
  3. Redis가 떠 있지 않으면 캐시 경로가 DB 폴백으로 동작한다(앱은 뜨지만 WARN 로그가 쌓인다).
  4. 기본 포트는 8080, Swagger는 /swagger-ui.html
  ```

### 테스트

```bash
./gradlew test
```

테스트는 H2 인메모리 DB와 `test` 프로필의 더미 설정으로 동작하므로 `.env`나 로컬 PostgreSQL/Redis 없이도 실행된다.

---

## 📎 기타 자료

#### 🎥 시연 영상: (https://drive.google.com/file/d/1MirNvxI5y35qS9tPtCIv9aWd2Gv4REeS/view)

#### 📑 발표 자료(PPT): 업데이트 예정

#### 🎥 노션 링크: (https://aquamarine-book-1e6.notion.site/2025-2-_-2-_1-26b77c61398180168bcfd3eee08b8e0c?source=copy_link)

#### 🎥 피그마 링크: (https://www.figma.com/design/YcCdV6Eqf486kKcZOK6mUm)

#### 🎥 피그잼 링크: (https://www.figma.com/board/TjRf47J8qvnVRsg2dXcSy9/)

#### 🎥 Swagger 링크: (https://yourtrip.cloud/swagger-ui/index.html)

#### 🎥 ERD 링크: (https://www.erdcloud.com/d/FvCG4hazXKR4vL8aq)
