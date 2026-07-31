# Redis 인기 코스 캐싱 전략

## 목표

이 전략은 **실무 수준의 대용량 트래픽이 몰리는 상황을 가정**하고, 그 조건에서도 흔들리지 않는 안정적인 서비스를 만드는 것을 최우선 목표로 한다. 캐시로 응답 속도를 올리는 것은 부차적인 효과이며, 핵심은 다음 두 가지다.

1. 트래픽이 집중되는 지점(인기 코스 목록/상세)에서 DB가 병목이 되지 않게 한다.
2. Redis 장애/지연이 발생해도 서비스 전체가 죽지 않게 한다(fail-open, 타임아웃, 스탬피드 방지).

## 배경 — 현재 구조의 문제

앱 홈 화면의 진입점은 인기 코스 목록이고, 여기서 상세로 들어가는 것이 가장 굵은 트래픽 동선이다. 현재 구조에는 다음 세 가지 문제가 있다.

**① 목록 조회가 매번 전체 스캔이다.**
`UploadCourseRepository.findAllByKeywordsOrderByViewCountDesc`([UploadCourseRepository.java:66-81](../src/main/java/backend/yourtrip/domain/uploadcourse/repository/UploadCourseRepository.java))는 `UploadCourse` 전체를 `LEFT JOIN FETCH` + 상관 서브쿼리로 훑고, 페이징이 없어 데이터가 늘수록 선형으로 느려진다.

**② 조회수가 DB에 반영되지 않는다.**
[UploadCourseServiceImpl.java:85-92](../src/main/java/backend/yourtrip/domain/uploadcourse/service/UploadCourseServiceImpl.java)의 `getDetail`은 `@Transactional(readOnly = true)`인데 그 안에서 `increaseViewCount()`를 호출한다.

- JPA는 트랜잭션 안에서 엔티티 필드를 바꾸면(`increaseViewCount()`), 트랜잭션 종료 시 자동으로 `UPDATE`를 날려주는 dirty checking을 수행한다.
- 그런데 `readOnly = true`로 선언하면 Spring/Hibernate는 "이 트랜잭션은 쓰기가 없다"고 가정하고 FlushMode를 `MANUAL`로 바꿔 자동 flush 자체를 꺼버린다.
- 그 결과 `increaseViewCount()`는 메모리상의 객체 값만 바꿀 뿐, 그 변경이 DB로 전송되지 않는다.
- 인기순 정렬(`ORDER BY viewCount DESC`)은 이 컬럼값을 기준으로 하므로, **정렬 기준 자체가 사실상 갱신되지 않고 있다.**

가장 단순한 해법은 `readOnly = true`를 제거하는 것이지만, 그러면 **상세 조회 1회 = DB `UPDATE` 1회**가 된다. 인기 코스는 정의상 동시에 여러 사용자가 몰려서 보게 되므로, 같은 row(`upload_course_id`)에 대한 `UPDATE`가 동시에 여러 건 발생한다. DB는 UPDATE 중인 row에 락을 걸기 때문에, 동시 요청들이 줄지어 순서대로 처리되는 **락 경합**이 생긴다. 트래픽이 적을 때는 티가 안 나지만, 대용량 트래픽 가정 하에서는 인기 코스일수록 상세 조회 자체가 느려지는 새로운 병목이 된다. 즉:

- 그대로 두면: 조회수 집계가 애초에 안 됨 (기능 결함)
- 단순히 고치면: 인기 코스일수록 상세 조회가 느려짐 (성능 병목)

**③ 상세 조회 1건의 비용이 목록보다 크다.**
`findWithMyCourseAndKeywords` → `existsById` → `getDaySchedulesWithPlaces`로 쿼리가 이어지고, **장소 이미지 개수만큼 presigned URL을 생성**한다([MyCourseServiceImpl.java:200-220](../src/main/java/backend/yourtrip/domain/mycourse/service/MyCourseServiceImpl.java)). 목록만 캐싱하면 트래픽을 막는 게 아니라 상세 조회로 떠넘기는 셈이 된다.

## 설계 원칙

1. **presigned URL은 캐싱하지 않는다.** [S3Service.java](../src/main/java/backend/yourtrip/global/s3/service/S3Service.java)의 presigned URL은 유효기간이 15분이다. 완성된 응답 DTO를 통째로 캐싱하면 캐시 TTL이 URL 만료에 종속되고, 만료된 URL이 나갈 위험이 생긴다. 따라서 **S3 key만 캐싱하고 URL은 응답 조립 시점에 생성**한다. presign은 네트워크 호출 없는 로컬 HMAC 서명이라 비용이 작다.

2. **조회수 증가는 캐싱 경계 바깥에 둔다.** 상세 조회 메서드에 `@Cacheable`을 그대로 걸면 캐시 히트 시 메서드 전체가 스킵되어 조회수 증가도 건너뛴다. 인기 코스일수록 히트율이 높으므로, 결과적으로 인기 코스의 조회수만 집계되지 않는 결과가 나온다. 조회수 증가는 캐시 조회와 분리된, 항상 실행되는 경로에 둔다.

3. **조회수 동기화 대상은 dirty set으로 추적한다.** 스케줄러가 `KEYS`/`SCAN`으로 전체 카운터 키를 훑지 않도록, 조회 발생 시 변경된 코스 ID를 Redis Set에 모아두고 스케줄러는 그 Set만 읽는다.

4. **캐시 스탬피드는 refresh-ahead + 분산 락으로 방지한다.** 인기 목록은 단일 키라 TTL 만료 순간 모든 동시 요청이 무거운 쿼리로 몰리는 데 취약하다. 조회수 동기화 스케줄러가 어차피 주기적으로 돌기 때문에, evict가 아니라 새 값을 계산해 **캐시에 덮어쓰는(put) 방식**으로 캐시가 비는 구간 자체를 없앤다. 서버 부팅 직후의 콜드 스타트 구간만 분산 락(`SET NX EX`)으로 별도 방어한다.

5. **fail-open은 짧은 타임아웃과 반드시 세트로 적용한다.** Lettuce의 기본 command timeout은 60초다. Redis가 완전히 죽은 게 아니라 응답이 느려지는 상황에서는 예외가 발생하지 않아 fail-open이 동작하지 않고, 요청 스레드가 60초씩 묶여 스레드 풀이 고갈된다. 즉 **타임아웃 없는 fail-open은 사실상 무의미**하다. 타임아웃을 짧게(1초) 설정해야 fail-open이 실질적으로 작동한다.

6. **갱신 주기**: 조회수 DB 동기화 10분(= 인기 목록 실질 갱신 주기), 인기 목록 안전망 TTL 30분(스케줄러가 멈췄을 때 대비), 상세 캐시 TTL 5분(± jitter 60초로 동시 만료 회피). 조회수는 API 응답에 노출되지 않으므로 사용자가 지연을 인지할 수 없는 수준이다.

## 문서 작성 원칙 — 개선점 기록

작업 진행 중 이번 체크리스트 항목의 범위를 벗어나지만 개발 시 참고할 만한 개선점(설정 미비, 잠재 리스크, 향후 강화가 필요한 사항 등)을 발견하면, **코드를 임의로 고치지 않고** 다음 두 곳에 동시에 남긴다.

1. 사용자에게 답변으로 알린다.
2. 해당 체크리스트 항목(`### N. ...`) 바로 아래에 `**추가 개선점**` 하위 섹션을 추가해 같은 내용을 기록한다.

## 적용 원칙 (진행 방식)

- 아래 체크리스트는 실제 구현 시 **한 항목씩** 적용한다.
- 중요하거나 개선 가능성이 있는 작업 구현 시 반드시 **사용자 확인**을 받은 뒤 다음 항목으로 넘어간다.
- 여러 항목을 한 번에 묶어 구현하지 않는다. 항목이 커 보이면 더 잘게 쪼갠다.
- 사용자에게 별도로 확인 받아야하는 중요한 결정 사항은 절대 임의로 결정하지 말고 사용자에게 물어본다
- **체크리스트 항목을 진행하다가 요구사항, 설계, 구현 방식 중 모르거나 애매한 부분이 있으면 절대 임의로 판단해서 진행하지 않는다. 반드시 작업을 멈추고 사용자에게 먼저 질문한 뒤, 답변을 받고 나서 진행한다.**
- 이 원칙은 [CLAUDE.md](../CLAUDE.md)의 "안정성 우선 원칙(대용량 트래픽 가정)"과 연결된다.
- 완료된 항목을 `- [x]`로 반영해야 한다.

## 적용 체크리스트

### 0. 사전 준비
- [x] 0-1. `build.gradle`에 `spring-boot-starter-data-redis`, `commons-pool2` 의존성만 추가
- [x] 0-2. `docker-compose.yml` 작성 (redis:7-alpine, maxmemory 정책 포함)
- [x] 0-3. `docker compose up -d` 후 `redis-cli ping`으로 로컬 기동 확인

**추가 개선점**
- `docker-compose.yml`에 영속성 볼륨(`volumes`)을 설정하지 않았다. 컨테이너가 재시작되거나 삭제되면 캐시 데이터가 전부 휘발된다. 순수 캐시 용도(원본은 항상 DB)라 서비스 정합성에는 문제가 없지만, 로컬 개발 중 반복적으로 캐시 값을 확인하려는 상황에서는 재기동마다 초기화된다는 점을 인지해야 한다. 필요하면 named volume(`redis-data:/data`) + `appendonly yes`를 추가하는 것을 검토할 수 있다.

### 1. Redis 연결 설정
- [x] 1-1. `application.yml`에 `spring.data.redis` 접속 정보(host/port)만 추가
- [x] 1-2. `timeout`/`connect-timeout` 1초로 설정 (기본 60초의 위험성: Redis 지연 시 요청 스레드가 60초씩 묶여 스레드 풀이 고갈됨)
- [x] 1-3. Lettuce pool 설정 추가 (`max-active`, `max-wait` 등 — `max-wait` 기본값 무한대기 방지)
- [x] 1-4. `.env.example`에 `REDIS_HOST`, `REDIS_PORT` 추가

**추가 개선점**
- `docker-compose.yml`의 Redis 컨테이너에 `requirepass`(인증)가 설정되어 있지 않고, `application.yml`에도 `spring.data.redis.password`가 없다. 로컬 개발 환경에서는 문제가 없지만, 운영 배포 시 (예: AWS ElastiCache, 또는 외부에서 접근 가능한 Redis) 인증 없이 노출되면 임의의 클라이언트가 캐시 데이터를 읽거나 `FLUSHALL` 등으로 서비스에 영향을 줄 수 있다. 배포 전에 `REDIS_PASSWORD` 환경변수 및 `spring.data.redis.password` 설정, 네트워크 수준 접근 제한(VPC/보안그룹)을 검토해야 한다.

### 2. 공통 캐시 인프라
- [ ] 2-1. `RedisConfig`에 `RedisTemplate<String,String>` 빈만 추가
- [ ] 2-2. `RedisConfig`에 `RedisCacheManager` 추가 (캐시 이름/TTL만 정의, 아직 사용처 없음)
- [ ] 2-3. `CacheErrorHandler` 구현 및 등록 (fail-open, WARN 로그 포함)
- [ ] 2-4. `GenericJackson2JsonRedisSerializer`에 `JavaTimeModule` 등록 확인

### 3. 인기 상위 5개 목록 — 읽기 경로부터 단계적으로
- [ ] 3-1. Repository에 상위 5개 ID만 조회하는 쿼리 추가 (캐싱 없이 기능만)
- [ ] 3-2. Repository에 ID 목록으로 `LEFT JOIN FETCH` 조회하는 쿼리 추가
- [ ] 3-3. `GET /api/upload-courses/popular` API 추가 (캐싱 없이 DB 직접 조회로 우선 동작 확인)
- [ ] 3-4. `PopularCourseCacheItem` 캐시 DTO 추가 (S3 key 보관)
- [ ] 3-5. 캐시 조회/저장 로직 추가 (콜드 스타트 락 없는 단순 버전)
- [ ] 3-6. 콜드 스타트 스탬피드 방지용 분산 락 추가

### 4. 상세 조회 캐싱
- [ ] 4-1. `UploadCourseDetailCacheItem` 캐시 DTO 추가 (S3 key 보관, 장소 이미지 포함)
- [ ] 4-2. TTL jitter 포함한 캐시 조회/저장 로직 추가
- [ ] 4-3. `getDetail`에 캐시 조회 연결 (이 시점까지는 조회수 로직은 건드리지 않음)

### 5. 조회수 Redis 카운터
- [ ] 5-1. `INCR` + `SADD` 기반 카운터 증가 로직 추가 (try-catch로 Redis 장애 격리)
- [ ] 5-2. `getDetail`의 기존 `increaseViewCount()` 호출을 Redis 카운터 호출로 교체
- [ ] 5-3. Repository에 벌크 증분 UPDATE 쿼리 추가
- [ ] 5-4. dirty set을 `RENAME`으로 스냅샷 떠서 배출하는 로직 추가
- [ ] 5-5. 스케줄러 추가 (DB 벌크 반영) + `@EnableScheduling` 등록
- [ ] 5-6. 스케줄러에 refresh-ahead(캐시 evict 대신 put) 연결

### 6. 캐시 무효화 연결
- [ ] 6-1. 코스 업로드 시 인기 목록 evict
- [ ] 6-2. fork 시 해당 코스 상세 캐시 evict
- [ ] 6-3. 원본 일정/장소 수정 시 업로드된 코스라면 상세 캐시 evict

### 7. 안정성 검증
- [ ] 7-1. 캐시 히트 시 SQL 로그가 발생하지 않는지 확인 (목록/상세 각각)
- [ ] 7-2. 캐시 히트 상황에서도 조회수 카운터는 정상 증가하는지 확인
- [ ] 7-3. `docker compose stop redis` 후 전 API가 5xx 없이 200으로 degrade하는지 확인 (fail-open)
- [ ] 7-4. Redis 중단 상태에서 응답 지연이 1초 내외인지 확인 (타임아웃 적용 검증)
- [ ] 7-5. refresh-ahead로 인기 목록 캐시 키가 evict 없이 값만 갱신되는지 확인

### 8. 문서 마무리
- [ ] 8-1. `CLAUDE.md` 반영 상태 최종 확인
- [ ] 8-2. 이 문서의 체크리스트를 실제 적용 결과에 맞춰 최종 갱신

## 범위에서 제외한 것

- **ZSET 실시간 랭킹** — 정렬 주체를 Redis로 옮기면 warm-up 로직, 소프트 삭제 코스 정리, 점수 재계산 불가 문제가 따라온다. 현재 규모에서 얻는 것보다 관리 부담이 크다.
- **검색어/태그 조합 캐싱** — 조합이 사실상 무한해 캐시 키가 폭발하고 히트율이 떨어진다.
- **기존 목록 API 페이징** — API 스펙 변경이라 FE 협의가 필요하므로 별도 작업으로 분리한다.
- **서킷 브레이커(Resilience4j)** — fail-open + 짧은 타임아웃으로 이번 목표는 충족된다. 추후 검토 대상.
