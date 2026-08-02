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

6. **갱신 주기**: 조회수 DB 동기화 10분(= 인기 목록 실질 갱신 주기), 인기 목록 안전망 TTL 30분(스케줄러가 멈췄을 때 대비), 상세 캐시 TTL 2시간(아이템 캐시와 동일, jitter 없음 — 자세한 재검토 근거는 [TASK-4.md](tasks/TASK-4.md) 참고). 조회수는 API 응답에 노출되지 않으므로 사용자가 지연을 인지할 수 없는 수준이다.

## 문서 작성 원칙

이 로드맵과, 섹션별 상세 기록(`docs/tasks/TASK-N.md`)을 나눠 쓰는 규칙은 [.claude/rules/roadmap-and-task-docs.md](../.claude/rules/roadmap-and-task-docs.md)를 따른다. 이 문서에는 체크리스트만 남기고, 설계 논의·발견한 버그·성능 측정 결과 같은 상세 내용은 해당 섹션의 TASK 파일에 적는다.

## 적용 원칙 (진행 방식)

- 아래 체크리스트는 실제 구현 시 **한 항목씩** 적용한다.
- 중요하거나 개선 가능성이 있는 작업 구현 시 반드시 **사용자 확인**을 받은 뒤 다음 항목으로 넘어간다.
- 여러 항목을 한 번에 묶어 구현하지 않는다. 항목이 커 보이면 더 잘게 쪼갠다.
- 사용자에게 별도로 확인 받아야하는 중요한 결정 사항은 절대 임의로 결정하지 말고 사용자에게 물어본다
- **체크리스트 항목을 진행하다가 요구사항, 설계, 구현 방식 중 모르거나 애매한 부분이 있으면 절대 임의로 판단해서 진행하지 않는다. 반드시 작업을 멈추고 사용자에게 먼저 질문한 뒤, 답변을 받고 나서 진행한다.**
- **매 `### N. ...` 섹션(0~8)이 끝날 때마다 애플리케이션을 실행해 정상 기동/동작하는지 확인한다.** 컴파일 성공만으로 끝내지 않고, 실제 구동(`bootRun` 등)까지 확인한 뒤 다음 섹션으로 넘어간다.
- **해당 섹션의 변경사항이 API 동작에 영향을 주는 경우, Playwright MCP로 E2E 검증을 실시한다.** 새 API 추가, 기존 API의 응답/동작 변경, 캐싱으로 인한 응답 경로 변경 등이 해당된다. 설정 추가처럼 API 동작에 영향이 없는 섹션은 대상에서 제외한다.
- 이 원칙은 [CLAUDE.md](../CLAUDE.md)의 "안정성 우선 원칙(대용량 트래픽 가정)"과 연결된다.
- 완료된 항목을 `- [x]`로 반영해야 한다.

## 적용 체크리스트

### 0. 사전 준비

> 발견한 개선점은 [TASK-0.md](tasks/TASK-0.md) 참고.

- [x] 0-1. `build.gradle`에 `spring-boot-starter-data-redis`, `commons-pool2` 의존성만 추가
- [x] 0-2. `docker-compose.yml` 작성 (redis:7-alpine, maxmemory 정책 포함)
- [x] 0-3. `docker compose up -d` 후 `redis-cli ping`으로 로컬 기동 확인

### 1. Redis 연결 설정
- [x] 1-1. `application.yml`에 `spring.data.redis` 접속 정보(host/port)만 추가
- [x] 1-2. `timeout`/`connect-timeout` 1초로 설정 (기본 60초의 위험성: Redis 지연 시 요청 스레드가 60초씩 묶여 스레드 풀이 고갈됨)
- [x] 1-3. Lettuce pool 설정 추가 (`max-active`, `max-wait` 등 — `max-wait` 기본값 무한대기 방지)
- [x] 1-4. `.env.example`에 `REDIS_HOST`, `REDIS_PORT` 추가

### 2. 공통 캐시 인프라
- [x] 2-1. `RedisConfig`에 `RedisTemplate<String,String>` 빈만 추가
- [x] 2-2. `RedisConfig`에 `RedisCacheManager` 추가 (캐시 이름/TTL만 정의, 아직 사용처 없음)
- [x] 2-3. `CacheErrorHandler` 구현 및 등록 (fail-open, WARN 로그 포함)
- [x] 2-4. `GenericJackson2JsonRedisSerializer`에 `JavaTimeModule` 등록 확인

### 3. 인기 상위 5개 목록 — 읽기 경로부터 단계적으로

> 설계 논의, 발견한 버그, 성능 측정 결과의 전체 기록은 [TASK-3.md](tasks/TASK-3.md) 참고.

- [x] 3-1. Repository에 상위 5개 ID만 조회하는 쿼리 추가 (캐싱 없이 기능만)
- [x] 3-2. Repository에 ID 목록으로 `LEFT JOIN FETCH` 조회하는 쿼리 추가
- [x] 3-3. `GET /api/upload-courses/popular` API 추가 (캐싱 없이 DB 직접 조회로 우선 동작 확인)
- [x] 3-4. `CourseListItemCacheItem` 캐시 DTO 추가 (S3 key 보관)
- [x] 3-5. 캐시 조회/저장 로직 추가 (콜드 스타트 락 없는 단순 버전, 랭킹/아이템 캐시 이원화 구조)
- [x] 3-6. 콜드 스타트 스탬피드 방지용 분산 락 추가
- [x] 3-7. `view_count` 컬럼에 인덱스 추가
- [x] 3-8. 서버 기동 시 인기 목록(전체+테마 7종, 8개 캐시 키) 웜업 추가
- [x] 3-9. 아이템 캐시가 타입 정보 유실로 항상 미스 처리되던 버그 수정 (4번 섹션 작업 중 소급 발견)

### 4. 상세 조회 캐싱

> 설계 논의, 발견한 버그, 성능 측정 결과의 전체 기록은 [TASK-4.md](tasks/TASK-4.md) 참고.

- [x] 4-1. `UploadCourseDetailCacheItem`/`DayScheduleCacheItem`/`PlaceCacheItem`/`PlaceImageCacheItem` 캐시 DTO 추가
- [x] 4-2. 캐시 조회/저장 로직 추가
- [x] 4-3. `getDetail`에 캐시 조회 연결
- [x] 4-4. Before/After 성능 측정 (단일 인기 코스 반복 조회 vs 여러 코스 혼합 조회)

### 5. 조회수 Redis 카운터
- [ ] 5-1. `INCR` + `SADD` 기반 카운터 증가 로직 추가 (try-catch로 Redis 장애 격리)
- [ ] 5-2. `getDetail`의 기존 `increaseViewCount()` 호출을 Redis 카운터 호출로 교체
- [ ] 5-3. Repository에 벌크 증분 UPDATE 쿼리 추가
- [ ] 5-4. dirty set을 `RENAME`으로 스냅샷 떠서 배출하는 로직 추가
- [ ] 5-5. 스케줄러 추가 (DB 벌크 반영) + `@EnableScheduling` 등록
- [ ] 5-6. 스케줄러에 refresh-ahead(캐시 evict 대신 put) 연결

### 6. 캐시 무효화 연결

> dev 브랜치 병합 배경과 알려진 gap은 [TASK-6.md](tasks/TASK-6.md) 참고.

- [ ] 6-1. 코스 업로드 시 인기 목록 evict
- [ ] 6-2. fork 시 해당 코스 상세 캐시 evict
- [ ] 6-3. 업로드 코스 직접 수정(`PUT /api/upload-courses/{id}`) 시 상세/아이템 캐시 evict 또는 write-through

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

## 향후 계획 — Redis 고가용성(Master-Replica)

현재는 `docker-compose.yml`에 정의된 단일 Redis 인스턴스로 구성되어 있다. 이 캐시는 원본이 항상 DB에 있는 **순수 캐시**([설계 원칙](#설계-원칙) 참고)이므로 인스턴스가 죽어도 데이터 정합성 자체는 깨지지 않는다. 다만 단일 인스턴스가 재시작/장애로 초기화되면 **캐시가 한 번에 전부 비워져서, [설계 원칙 4번](#설계-원칙)에서 방어하려는 콜드 스타트 스탬피드 상황이 그대로 재현**된다는 가용성 문제가 남는다.

- **목표**: Master-Replica 구성(추후 Sentinel 등으로 자동 failover까지 확장)으로 마스터 장애 시에도 레플리카가 캐시를 계속 서빙하게 해, 캐시가 전면적으로 비워지는 빈도와 그로 인한 DB 부하 스파이크를 줄인다.
- **적용 시점**: 이번 체크리스트(0~8) 범위 밖이며, 별도 작업으로 분리해 추후 진행한다. 구체적인 구성 방식(Docker Compose 기반 Sentinel, 관리형 Redis 서비스 전환 등)은 착수 시점에 다시 논의한다.

### 현재 구조가 이 계획에 미치는 영향 (사전 검토)

현재까지 만든 코드/설정은 이 계획을 막지 않는다. [RedisConfig.java](../src/main/java/backend/yourtrip/global/config/RedisConfig.java)의 `redisTemplate`, `redisCacheManager` 빈은 모두 Spring Boot가 `spring.data.redis.*` 프로퍼티로 자동 구성해주는 `RedisConnectionFactory`를 주입받아 쓸 뿐, 연결 토폴로지(단일 노드/Sentinel/Cluster)를 코드 어디에서도 직접 알지 않는다. 즉 **`RedisConfig`, `RedisCacheErrorHandler`는 Master-Replica 전환 시 수정할 필요가 없다** — 아래 항목들은 기존 것을 고치는 게 아니라 새로 추가하는 작업이다.

- **Sentinel 없이 master + replica만 두는 구성은 진짜 HA가 아니다.** Lettuce/Spring은 master 장애를 자동 감지해 replica를 승격시켜주지 않는다. 자동 failover가 목표라면 최소 3대(quorum 확보용 홀수)의 Sentinel이 함께 필요하며, `application.yml`의 `spring.data.redis.host`/`port`(단일 노드 전용)를 `spring.data.redis.sentinel.master`/`sentinel.nodes`로 교체해야 한다 — 이 교체만으로 Spring Boot가 Sentinel-aware 커넥션을 자동 구성하므로 Java 코드 변경은 불필요하다.
- `docker-compose.yml`에는 현재 `redis` 서비스 하나만 정의되어 있다. `--replicaof` 옵션을 가진 replica 서비스, (Sentinel을 쓴다면) sentinel 서비스들을 추가하면 되고 기존 `redis` 서비스 정의와 충돌하지 않는다.
- 읽기 트래픽까지 replica로 분산하려면 Lettuce `ReadFrom` 설정이 별도로 필요하다(기본값은 읽기/쓰기 모두 master로만 라우팅). 조회수 카운터(`INCR`/`SADD`, 5단계에서 추가 예정)는 쓰기이므로 이 설정과 무관하게 항상 master로 간다.
- [TASK-0.md](tasks/TASK-0.md)에 이미 기록한 볼륨 미설정 이슈도 함께 고려한다 — replica가 재기동될 때 마스터의 영속 데이터가 없으면 매번 풀 리싱크(전체 데이터 재동기화) 비용이 발생한다.
