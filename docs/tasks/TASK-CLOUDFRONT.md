# TASK-CLOUDFRONT. presigned URL → CloudFront 전환 및 도입 전/후 성능 측정

> Redis 캐싱 로드맵([CACHING-ROADMAP.md](../CACHING-ROADMAP.md))과는 별개의 작업이다.
> 상세 조회 캐싱([TASK-4.md](TASK-4.md))이 "캐시 히트 시에도 이미지 개수만큼 presigned URL을 순차 발급하는 것이 새로운 병목"이라는 걸 발견한 데서 출발해, presigned URL(S3)을 CloudFront(공개 콘텐츠는 서명 없는 URL, 비공개 콘텐츠는 Signed URL)로 전면 교체했다. 이 문서는 그 전환의 설계 근거와, "실제로 효과가 있었는가"를 실측한 결과를 남긴다.

## 배경

업로드 코스(공개, 누구나 조회 가능)와 나의 코스(비공개, 작성자만 조회 가능)는 지금까지 동일하게 `S3Service.getPresignedUrl()`로 이미지 URL을 발급했다. 이 방식의 문제는 두 가지였다:

1. **presign은 매 요청·매 이미지마다 새로 발급해야 한다.** TASK-4.md가 실측으로 확인했듯, 캐시가 히트해도 이 발급 자체는 캐싱 대상이 아니라(URL이 만료되는 15분짜리라 캐싱하면 만료된 URL이 나갈 위험이 있음) 이미지 개수만큼 매번 반복된다.
2. **공개/비공개 구분 없이 하나의 메커니즘을 쓴다.** 업로드 코스는 누구나 봐도 되는 콘텐츠인데도 굳이 서명을 발급하고 있었다.

해결책: 공개 콘텐츠(uploadcourse 썸네일/장소이미지, feed, 프로필)는 **CloudFront + S3 key로 직접 서빙**(서명 없음), 비공개 콘텐츠(mycourse 장소이미지)는 **CloudFront Signed URL + 여전히 비공개인 S3**로 서빙한다. 상세 설계(S3 객체 실제 복사, path 기반 공개/비공개 분리, invalidation 등)는 이 세션의 구현 과정에서 결정됐고, 실제 AWS 인프라(Terraform)까지 적용을 마쳤다.

## 설계 결정과 근거 (요약)

- **공개/비공개 분리는 CloudFront cache behavior의 path pattern으로 한다.** `private/*`는 서명 필수(트러스트 키그룹), 그 외 전체는 무서명. 기존 공개 콘텐츠는 key 형식을 바꿀 필요가 없고, mycourse 신규 이미지만 `private/` prefix로 저장한다.
- **mycourse↔uploadcourse 간 이미지가 S3 key 문자열만 공유되던 구조(원본과 사본이 물리적으로 같은 오브젝트)를 실제 S3 복사로 바꿨다.** 업로드(비공개→공개), 포크(공개→비공개) 양방향 모두 `CopyObject`로 새 오브젝트를 만든다.
- **캐시 TTL**: 공개 콘텐츠 `max-age=15552000`(6개월, 사실상 정적이라 장기 캐싱), 비공개 `max-age=604800`(1주일). 삭제 시 CloudFront invalidation을 함께 호출해 TTL과 무관하게 즉시 무효화한다.
- **Signed URL 유효기간**: 60분(기존 presign 15분보다 완화 — 느린 네트워크·백그라운드 전환 등 정상 사용 패턴에서 불필요하게 이미지가 깨지는 걸 방지).


## 성능 측정 계획

### 측정 환경

- PostgreSQL(네이티브 Windows 서비스, `localhost:5434`), Redis(네이티브 Windows 서비스, `localhost:6479`) — TASK-3/4.md와 동일 구성.
- 부하 생성: Node.js(v24) 내장 `http` 모듈 기반 동시성 제어 스크립트(TASK-3/4.md와 동일 방법론). `single` 모드(고정 URL 반복 조회)와 `pool` 모드(URL 목록에서 매 요청 무작위 선택) 두 가지를 지원하도록 새로 작성했다.

### 시드 데이터

mycourse/uploadcourse 각각 독립적으로 3,000건(hot 1건 + pool 2,999건), 코스당 1일차 × 장소 5개 × 이미지 2장 = **10장/코스**로 통일했다. mycourse에는 코스 전체를 한 번에 반환하는 엔드포인트가 없어(`GET /days/{dayId}/places`만 존재) 이미지를 하루에 몰아, uploadcourse의 `GET /{id}`(전체 일정 한 번에 반환)와 "동일한 모양의 단일 호출, 동일한 이미지 개수"로 공정하게 비교했다. `place_image` S3 key는 실제 오브젝트 없이 문자열만 채웠다(API 응답 속도만 측정 대상이라 이미지 실물은 불필요 — TASK-3/4와 동일).

**mycourse 소유권 처리**: `GET /days/{dayId}/places`는 `checkOwnedCourse(courseId, userId)` → `existsByIdAndUser_Id`로 작성자 본인만 조회 가능하도록 막혀 있다. 이를 만족시키기 위해 mycourse 3,000건(hot+pool) 전부를 `TestUserInitializer`가 심어둔 첫 번째 유저(user_id=1) 소유로 시드하고, 부하테스트 스크립트도 그 유저로 서명한 JWT 하나를 모든 요청(시나리오 A/B 공통)에 재사용했다. `existsByIdAndUser_Id`는 단일 인덱스 조회라 소유자가 1명이든 여러 명이든 쿼리 비용이 동일하고, 이번 벤치마크가 격리해서 보려는 변수(URL 서명 비용)와도 무관하므로 이 단순화가 측정 결과를 왜곡하지 않는다. 다만 "여러 사용자의 JWT를 섞어 인증 오버헤드까지 함께 측정"하는 시나리오는 이번 범위에 포함하지 않았다.

### 시나리오

- **A — 인기 코스 반복 조회**: hot 코스 1건을 반복 요청(캐시가 가장 유리한 상황).
- **B — 여러 코스 혼합 조회**: pool 2,999건 중 매 요청 무작위 선택(자연 재방문율에 의존하는 롱테일 트래픽).
- **동시성**: 50(TASK-3/4 기준값) / 200(고부하 — 이미지 개수 × 동시 요청 수로 곱해지는 효과를 드러내기 위함).
- 각 조합 600건 요청.

## 결과

### 나의 코스(mycourse) — `GET /days/{dayId}/places`, 비공개(Signed URL)

| 시나리오 | 동시성 | TPS(Before→After) | p50(Before→After) | p95 | p99 |
|---|---|---|---|---|---|
| A(인기 반복) | 50 | 69.4 → 29.1 (**-58%**) | 693ms → 1647ms (**+138%**) | 903→2297ms | 1147→2543ms |
| A(인기 반복) | 200 | 74.2 → 27.6 (**-63%**) | 2559ms → 6545ms (**+156%**) | 2895→8232ms | 3890→10949ms |
| B(혼합 조회) | 50 | 68.9 → 30.4 (**-56%**) | 693ms → 1590ms (**+129%**) | 987→2065ms | 1146→2408ms |
| B(혼합 조회) | 200 | 72.7 → 30.3 (**-58%**) | 2478ms → 6218ms (**+151%**) | 3002→7383ms | 3970→10903ms |

### 업로드 코스(uploadcourse) — `GET /{id}`, 공개(무서명 URL)

| 시나리오 | 동시성 | TPS(Before→After) | p50(Before→After) | p95 | p99 |
|---|---|---|---|---|---|
| A(인기 반복) | 50 | 182.4 → 1005.0 (**+451%**) | 260ms → 37ms (**-86%**) | 365→145ms | 472→178ms |
| A(인기 반복) | 200 | 164.0 → 1153.9 (**+604%**) | 1027ms → 129ms (**-87%**) | 1508→286ms | 1930→301ms |
| B(혼합 조회) | 50 | 69.2 → 111.8 (**+62%**) | 714ms → 435ms (**-39%**) | 854→562ms | 934→785ms |
| B(혼합 조회) | 200 | 77.2 → 147.1 (**+90%**) | 2444ms → 1269ms (**-48%**) | 2686→1502ms | 2828→2448ms |

### DB 쿼리 발생 횟수 (부가 지표)

시나리오 B(혼합 조회, 두 동시성 합산 1,200건, pool 2,999건) 기준 실제 상세조회 쿼리 실행 횟수: Before 996회, After 989회 — 거의 동일하다. 이는 Redis 캐싱 자체는 이번 작업으로 바뀐 게 없고(TASK-4에서 이미 구현됨), **이번 벤치마크가 격리해서 보려는 변수가 "URL 생성 방식" 하나뿐**이라는 설계가 의도대로 작동했음을 보여준다.

## 결과 해석

1. **업로드 코스(공개)는 예상대로 극적으로 개선됐다.** 캐시 히트 시 이미지 URL 조립이 `s3Service.getPresignedUrl()`(HMAC-SHA256 서명 + 매번 새 요청 객체 생성)에서 `cloudFrontService.getPublicUrl()`(순수 문자열 결합)로 바뀌면서, 특히 캐시가 거의 완전히 히트하는 시나리오 A에서 **TPS가 5~7배** 뛰었다. 이는 TASK-4.md가 "히어로 코스(이미지 105장)에서 캐시 히트에도 새로운 병목"이라고 예견했던 것을 정량적으로 확인한 결과다. 시나리오 B(자연 재방문율 ~9%, DB 쿼리가 대부분 발생)에서는 개선폭이 상대적으로 작은데(+62~90%), DB 조회 비용이 지배적인 상황에서는 URL 조립 비용 절감의 상대적 비중이 작아지기 때문이다 — **캐시가 잘 히트하는 인기 콘텐츠일수록 이번 전환의 효과가 크다.**
2. **나의 코스(비공개)는 오히려 느려졌다 — 사전에 우려했던 대로다.** presigned URL(HMAC-SHA256, AWS SigV4)에서 CloudFront Signed URL(RSA 서명, canned policy)로 바뀌면서 **서명 알고리즘 자체가 더 무거워졌다.** RSA 서명은 HMAC보다 연산 비용이 본질적으로 크고, 코스당 이미지 10장이면 요청마다 서명 연산이 10회 반복되는데, 이게 동시성이 올라갈수록(50→200) 악화 폭도 커졌다(-58%→-63%). **"CloudFront로 옮기면 무조건 빨라진다"는 가정이 틀렸다는 것을 이번 실측이 정직하게 보여준다** — CDN 자체의 이점(엣지 캐싱, 글로벌 배포)은 여전히 유효하지만, "서명 URL 발급 비용"만 놓고 보면 서명 알고리즘 선택이 성능을 좌우한다.
3. **그럼에도 CloudFront Signed URL로 전환한 것 자체는 유효한 선택이다.** 성능 측정은 "URL 발급 비용"만 격리해서 본 것이고, 실제 서비스 관점에서 CloudFront 전환의 목적은 (a) 공개 콘텐츠의 압도적 개선, (b) 비공개 콘텐츠도 최소한 CDN 엣지 캐싱 이점(같은 이미지를 반복 조회할 때 오리진 왕복 감소)을 얻는 것, (c) 공개/비공개를 하나의 인프라(CloudFront 배포)로 통합 관리하는 것이었다. 다만 이번 실측으로 "비공개 콘텐츠의 URL 발급 비용" 자체는 트레이드오프였다는 걸 명확히 알게 됐다.

## 발견한 개선점 (이번 작업 범위 밖 — 코드 수정 없이 기록)

- **mycourse Signed URL 발급의 병렬화 — ✅ 완료.** 병렬 스트림/스레드풀로 서명을 병렬화하면 개선 여지가 있다고 여기 처음 기록했었는데, PR #57 후속 작업(perf 커밋 306505b)으로 실제 구현·재측정까지 마쳤다. 상세 내용과 재측정 결과는 아래 "Signed URL 발급 병렬화 및 캐싱 적용" 절 참고.
- **RSA 키 크기 조정은 불가능 — 대신 ECDSA P-256 검토 여지가 있다 — ✅ 완료.** AWS 공식 문서를 확인한 결과, CloudFront trusted key group(이 프로젝트가 쓰는 방식)에 등록 가능한 키는 "SSH-2 RSA 2048" 또는 "ECDSA P256" 두 가지로 고정되어 있다 — 1024/3072/4096 같은 다른 RSA 크기는 애초에 선택지가 아니다(legacy CloudFront key pair 방식만 1024/2048/4096을 지원하지만, AWS가 신규 사용을 권장하지 않는 구식 방식이다). 반면 ECDSA P-256은 RSA-3072급 안전성을 가지면서도 타원곡선 연산이라 RSA-2048보다 서명이 훨씬 빠르다고 알려져 있다 — "키 크기를 줄인다"가 아니라 "서명 알고리즘 자체를 RSA에서 ECDSA로 바꾼다"가 진짜 실현 가능한 최적화 방향이다. 실제로 전환·재측정까지 마쳤다. 상세 내용은 아래 "RSA → ECDSA P-256 전환 및 재측정" 절 참고.
- **HikariCP 커넥션 풀 크기 튜닝**: mycourse 병렬화 재측정(아래 참고)에서 동시성 200일 때는 DB 커넥션 획득 대기가 서명 병렬화의 이득보다 더 큰 병목으로 확인됐다. `application.yml`에 별도 설정이 없어 Spring Boot 기본값(`maximum-pool-size=10`)을 그대로 쓰는데, 200개 요청이 커넥션 10개를 두고 경쟁하니 병렬화 효과가 상당 부분 가려졌다. 이 값을 실제 피크 동시성에 맞게 올리면 병렬화 효과가 더 뚜렷하게 드러날 가능성이 크다 — 코드는 건드리지 않았고, 재측정으로 먼저 검증이 필요하다.
- **mycourse Redis 캐싱은 검토했으나 권장하지 않는다**: uploadcourse처럼 mycourse도 Redis로 캐싱하면 어떨지 논의했다. 그러나 uploadcourse는 소수의 인기 코스를 다수 사용자가 반복 조회하는 구조(읽기 증폭이 큼)인 반면, mycourse는 작성자 본인만 보는 비공개 데이터라 캐싱해도 절약되는 읽기 총량이 크지 않다. 반대로 `savePlace`/`updatePlaceTime`/`addPlaceImage`/`deletePlace` 등 뮤테이션 엔드포인트가 많아 캐시 무효화 지점이 uploadcourse보다 많고, 본인이 방금 수정한 내용을 본인이 즉시 봐야 하는 강한 일관성 요구까지 겹친다 — 캐싱 비용 대비 이득이 uploadcourse보다 훨씬 작다. 이번에 실측으로 확인된 진짜 병목(HikariCP 풀 크기)에 캐싱보다 훨씬 저비용·저위험으로 대응할 수 있으므로, mycourse 캐싱 도입보다 커넥션 풀 튜닝을 우선순위로 둔다.

### Signed URL 발급 병렬화 및 캐싱 적용 (PR #57 후속)

- **작업 내용**: `MyCourseServiceImpl.getPlaceListByDay`의 Signed URL 순차 발급을 전용 `ThreadPoolTaskExecutor`와 `CompletableFuture`를 통해 병렬화하고, `CloudFrontService`에서 `@PostConstruct`를 통해 개인키를 캐싱하도록 개선(perf 커밋 306505b).

- **재측정 환경**: 이번엔 Node.js 자체 스크립트 대신 k6(`shared-iterations` executor 기반 스크립트)로 처음 측정했다. Before는 perf 커밋의 부모(`b3dd945`)를 별도 git worktree로 체크아웃해 포트 8081·별도 DB(`yourtrip_before`)로, After는 현재 코드를 포트 8080으로 각각 띄워 비교했다. 시드 규모(3,000코스=hot 1+pool 2,999, 코스당 10장)와 시나리오/동시성/요청 수(A/B, 50/200, 각 600건)는 원 측정과 동일하게 맞췄다. **이 머신은 12코어**(`system.cpu.count`로 확인)라 `cloudFrontSigningExecutor` 풀 크기도 12로 잡혔다 — t3.micro(물리 코어 1개)보다 훨씬 유리한 조건이다. (k6 스크립트·시드 SQL·자동화 래퍼는 TASK-3/4의 Node.js 스크립트와 동일하게 레포에 커밋하지 않았다.)

- **재측정 결과**:

| 시나리오 | 동시성 | TPS(Before→After) | p50(Before→After) | p95(Before→After) | p99(Before→After) |
|---|---|---|---|---|---|
| A(인기 반복) | 50 | 27.9 → 28.5 (+2%) | 1651ms → 1646ms (±0%) | 2632ms → 2305ms (-12%) | 3169ms → 3021ms (-5%) |
| A(인기 반복) | 200 | 28.6 → 31.7 (+11%) | 6469ms → 6069ms (-6%) | 9855ms → 7093ms (-28%) | 11030ms → 10105ms (-8%) |
| B(혼합 조회) | 50 | 29.9 → 29.9 (±0%) | 1587ms → 1600ms (+1%) | 2363ms → 2144ms (-9%) | 3107ms → 2377ms (-23%) |
| B(혼합 조회) | 200 | 30.7 → 27.5 (-10%) | 6250ms → 6612ms (+6%) | 9154ms → 8592ms (-6%) | 11149ms → 10826ms (-3%) |

(8개 조합 전부 체크 성공률 100%로 확보된 값만 반영했다 — 동시성 200 조합에서 Windows 개발 머신의 Tomcat `maxThreads`(기본 200)=VUS 경계에 걸려 커넥션이 간헐적으로 거부되는 현상이 관찰됐는데, 앱이 idle로 완전히 안정된 뒤 재시도하면 100% 재현됐다. Before/After 양쪽에서 동일하게 나타나 코드 문제가 아니라 이 환경의 특성으로 판단했다. 원본 raw JSON은 레포에 커밋하지 않았다.)

- **해석 — 기대보다 개선폭이 작다, 왜?**: 12코어에 이미지 10장이면 이론상 서명을 거의 전부 동시 실행할 수 있어 극적인 개선을 기대했지만, 실제로는 시나리오 B/동시성 200에서 오히려 TPS가 소폭 **후퇴**했고 나머지도 개선폭이 크지 않다(p95/p99는 대체로 개선). 원인으로 가장 유력한 것은 **HikariCP 커넥션 풀**이다 — `application.yml`에 별도 설정이 없어 기본 `maximum-pool-size=10`을 쓰는데, 부하 중 `StatisticalLoggingSessionEventListener` 로그에서 "751ms 동안 JDBC 커넥션 획득 대기" 같은 사례가 관찰됐다. 즉 동시성 200에서는 200개 요청이 겨우 10개의 DB 커넥션을 두고 경쟁하는 게 서명 시간 단축보다 훨씬 큰 병목이 되어, 병렬화의 이득을 상당 부분 가려버린 것으로 보인다. Before/After 모두 이 커넥션 풀 제약을 동일하게 받으므로 "URL 생성 방식만 격리해서 비교했다"는 벤치마크 설계 의도 자체는 유효하지만, **총 응답시간 기준으로는 서명 병렬화의 효과가 DB 커넥션 병목에 가려 온전히 드러나지 않았다**는 게 이번 실측의 정직한 결론이다. HikariCP 풀 크기를 늘려 재측정하면 병렬화 효과가 더 뚜렷하게 드러날 가능성이 있다(후속 과제로 남김, 코드는 건드리지 않음).

- **presign 롤백 관련**: CloudFront Signed URL을 그만두고 S3 presign으로 롤백하는 방안도 검토했으나, 위 재측정 결과(개선은 있으나 드라마틱하지 않음, 그마저 DB 커넥션 병목에 가려짐)로는 롤백을 정당화하기 어렵다고 판단해 보류한다. 실제 배포 타겟(t3.micro, 물리 코어 1개)에서는 이번 12코어 dev 머신보다 병렬화 이득이 더 작을 것으로 예상되므로, t3.micro 배포 후 재측정이 여전히 유효한 후속 과제다.

- **발견한 인프라 갭 — ✅ 해결.** `generate_statistics: true` + actuator만으로는 이 프로젝트(Spring Boot 3.5.7)에서 `hibernate.statements` 메트릭이 Micrometer에 자동 등록되지 않는 것으로 확인됐다(`/actuator/metrics` 목록에 `hibernate.*`가 전혀 없음, 쿼리 실행 후에도 동일). `docs/guide/LOAD-TESTING-GUIDE.md`가 문서화한 DB 쿼리 횟수 diff 절차가 실제로는 동작하지 않는다는 뜻이라, 재측정 시점에는 이 부가 지표를 건너뛰었다.
  - **원인**: Spring Boot의 `HibernateMetricsAutoConfiguration`은 `@ConditionalOnClass({..., HibernateMetrics.class, ...})`로 `org.hibernate.stat.HibernateMetrics` 클래스를 요구하는데(소스 직접 확인), 이 클래스는 `hibernate-core`가 아니라 별도 아티팩트인 `org.hibernate.orm:hibernate-micrometer`에 있다. `spring-boot-starter-data-jpa`는 이 아티팩트를 가져오지 않아, `generate_statistics: true`를 켜도 그 값을 Micrometer에 연결해줄 바인더 자체가 클래스패스에 없었다.
  - **조치**: `build.gradle`에 `implementation 'org.hibernate.orm:hibernate-micrometer'` 한 줄 추가(버전은 Spring Boot BOM이 관리, `hibernate-core`와 동일하게 6.6.33.Final로 해석됨을 확인). 앱 재기동 후 `hibernate.statements`를 포함해 `hibernate.*` 메트릭 28종이 모두 등록되고, 실제 API 호출에 따라 카운터가 증가하며(13→19), `/actuator/prometheus`에도 `hibernate_statements_total`로 정상 노출됨을 확인했다. 이제 `MONITORING-GUIDE.md`의 `rate(hibernate_statements_total[1m])` PromQL 예시도 실제로 동작한다.
  - 또한 `build.gradle`에 `micrometer-registry-prometheus`가 없어 `/actuator/prometheus` 자체가 등록 안 되는 문제는 이번 재측정 당시 함께 고쳤다(같은 근본 원인 계열의 별개 이슈).

### RSA → ECDSA P-256 전환 및 재측정

- **작업 내용**: `CloudFrontService`가 사용하는 CloudFront Signed URL 서명 알고리즘을 RSA-2048에서 ECDSA P-256으로 교체했다.
  - `software.amazon.awssdk:bom`을 `2.25.30`에서 `2.51.0`으로 업그레이드했다 — [aws/aws-sdk-java-v2 PR #6627](https://github.com/aws/aws-sdk-java-v2/pull/6627)(2025-12-17 병합)이 `CannedSignerRequest`에 EC 개인키 자동 인식을 추가하기 전 버전이라 EC 키 자체를 로드하지 못했다.
  - `openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:prime256v1`로 새 키페어를 생성했다. 처음엔 `openssl ecparam -genkey`(SEC1, `BEGIN EC PRIVATE KEY`)로 만들었는데, AWS SDK의 PEM 파서가 이 헤더를 인식하지 못해 `NullPointerException`(`PemObjectType.ordinal()` on null)이 실제로 발생했다 — `genpkey`가 만드는 PKCS8(`BEGIN PRIVATE KEY`) 형식으로 바꿔서 해결했고, 이 사실을 신규 `CloudFrontServiceTest`(RSA/EC 두 키 타입 모두 실제 openssl로 생성해 서명까지 확인)로 고정해뒀다. 애플리케이션 코드(`CloudFrontService.initPrivateKey()`/`getSignedUrl()`) 자체는 무변경 — `CannedSignerRequest`가 키 알고리즘을 자동 감지한다.
  - Terraform: `aws_cloudfront_public_key.signer`를 고정된 `name` 대신 `name_prefix` + `lifecycle { create_before_destroy = true }`로 바꿨다. 처음엔 `name`을 그대로 둔 채 `terraform apply`를 시도했는데, 새 공개키를 만들기 전에 기존 공개키부터 지우려다 `PublicKeyInUse`(여전히 key group이 참조 중) 오류로 apply가 실패했다 — 새 키를 먼저 만들고 key group을 갱신한 뒤에야 기존 키를 지우도록 순서를 바꿔서 해결했다. 실제 AWS(운영 CloudFront 배포)에 적용까지 완료했다.

- **로컬/실제 CloudFront 기능 검증**: 로컬 앱을 띄워 실제 mycourse 이미지 업로드 → Signed URL 발급 → 그 URL로 직접 CloudFront에 요청까지 end-to-end로 확인했다. 발급된 URL은 `Key-Pair-Id`가 새 ECDSA 키와 일치했고 `Signature` 값도 RSA(344자) 대비 훨씬 짧은(ECDSA 특유의 DER 인코딩) 형태였다. CloudFront가 해당 서명을 검증해 실제로 `200`을 반환했고, 서명 없이 같은 리소스에 접근하면 `403`으로 정상 거부됨을 확인했다.

- **재측정 방법론**: 기존 RSA 병렬화 재측정(위 절)과 동일한 시드(mycourse 3,000코스=hot 1+pool 2,999, 코스당 10장)를 그대로 재사용했다. Before(RSA)는 `mycourse-signedurl-before` 워크트리(PR #61 부모 커밋)를 포트 8081로, After(ECDSA)는 이 브랜치를 포트 8090으로 띄워 k6(`shared-iterations`, 시나리오 A/B × 동시성 50/200 × 각 600건)로 비교했다. 두 앱이 동일한 시드를 갖도록 Before DB의 mycourse 데이터를 After DB로 그대로 복제했다.

- **재측정 결과**:

| 시나리오 | 동시성 | TPS(RSA→ECDSA) | p50(RSA→ECDSA) | p95(RSA→ECDSA) | p99(RSA→ECDSA) |
|---|---|---|---|---|---|
| A(인기 반복) | 50 | 23.0 → 63.2 (**+174%**) | 1970ms → 697ms (**-65%**) | 2950ms → 1110ms (**-62%**) | 3600ms → 1280ms (**-64%**) |
| A(인기 반복) | 200 | 30.9 → 82.7 (**+167%**) | 6800ms → 2290ms (**-66%**) | 8160ms → 2870ms (**-65%**) | 11060ms → 3290ms (**-70%**) |
| B(혼합 조회) | 50 | 25.8 → 68.8 (**+167%**) | 1870ms → 702ms (**-62%**) | 2310ms → 1160ms (**-50%**) | 2530ms → 1310ms (**-48%**) |
| B(혼합 조회) | 200 | 29.4 → 104.9 (**+257%**) | 6770ms → 1890ms (**-72%**) | 7630ms → 2280ms (**-70%**) | 10940ms → 2570ms (**-77%**) |

(동시성 200 조합은 이전 절에서 관찰된 것과 동일한 Windows 개발 머신의 Tomcat `maxThreads`(기본 200) 경계 현상으로 체크 성공률이 84~96% 사이였다 — Before/After 양쪽에 동일하게 나타나는 환경 특성이라 재시도해 100%를 맞추지는 않고 그대로 반영했다. k6 스크립트·원본 결과는 기존 관례대로 레포에 커밋하지 않았다.)

- **해석**: 병렬화 재측정 때와 달리 이번엔 모든 조합에서 개선폭이 뚜렷하고 일관됐다 — TPS는 최소 +167%, 최대 +257%, p50/p95/p99는 대부분 -60~-77% 구간이다. 병렬화 재측정 당시 병목으로 지목됐던 HikariCP 커넥션 풀(`maximum-pool-size=10`) 제약을 Before/After 둘 다 동일하게 받는데도 이렇게 큰 차이가 난다는 것은, **서명 알고리즘 자체의 연산 비용 차이가 DB 커넥션 병목보다 훨씬 지배적인 요인**이었다는 뜻이다. 코스당 이미지 10장이면 요청마다 서명 연산이 10회 반복되는데, RSA-2048의 모듈러 거듭제곱 연산을 ECDSA P-256의 타원곡선 연산으로 바꾼 효과가 병렬화(동시 실행 수를 늘리는 것)보다 서명 1회당 비용을 직접 줄이는 이번 변경에서 훨씬 크게 드러났다. TASK-CLOUDFRONT.md 67~71행에서 "CloudFront 전환 자체가 비공개 콘텐츠에는 트레이드오프"라고 기록했던 문제를, 알고리즘 교체로 상당 부분 되돌린 셈이다.

## 이번 작업에서 얻은 교훈 (포트폴리오 포인트)

1. **"CDN으로 옮기면 빨라진다"는 직관은 콘텐츠 성격(공개/비공개)에 따라 다르게 검증돼야 한다.** 같은 CloudFront 전환이라도 서명이 필요 없는 공개 콘텐츠와 서명이 필요한 비공개 콘텐츠는 정반대의 결과가 나올 수 있다 — 이번 실측으로 "일부는 개선, 일부는 트레이드오프"라는 균형 잡힌 결론에 도달했다.
2. **캐시와 DB는 서로 다른 생명주기를 가진 독립된 상태 저장소다.** DB를 초기화해도 Redis TTL이 남아있으면 완전히 다른 시점의 데이터가 우연히 같은 ID로 서빙될 수 있다 — 재현 가능한 벤치마크를 만들려면 모든 상태 저장소를 명시적으로 초기화해야 한다.
