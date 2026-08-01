package backend.yourtrip.domain.uploadcourse.service;

import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
import backend.yourtrip.domain.mycourse.entity.travelCourse.TravelCourse;
import backend.yourtrip.domain.mycourse.service.MyCourseService;
import backend.yourtrip.domain.uploadcourse.dto.cache.CourseListItemCacheItem;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.place.Place;
import backend.yourtrip.domain.mycourse.entity.place.PlaceImage;
import backend.yourtrip.domain.uploadcourse.dto.request.DayScheduleUpdateRequest;
import backend.yourtrip.domain.uploadcourse.dto.request.PlaceImageUpdateRequest;
import backend.yourtrip.domain.uploadcourse.dto.request.PlaceUpdateRequest;
import backend.yourtrip.domain.uploadcourse.dto.request.UploadCourseCreateRequest;
import backend.yourtrip.domain.uploadcourse.dto.request.UploadCourseUpdateRequest;
import backend.yourtrip.domain.uploadcourse.dto.response.CourseKeywordListResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseCreateResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseDetailResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseListResponse;
import backend.yourtrip.domain.uploadcourse.entity.CourseKeyword;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.domain.uploadcourse.entity.enums.UploadCourseSortType;
import backend.yourtrip.domain.uploadcourse.mapper.UploadCourseMapper;
import backend.yourtrip.domain.uploadcourse.repository.UploadCourseRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.S3ErrorCode;
import backend.yourtrip.global.exception.errorCode.UploadCourseErrorCode;
import backend.yourtrip.global.config.RedisConfig;
import backend.yourtrip.global.s3.service.S3Service;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadCourseServiceImpl implements UploadCourseService {

    private static final String POPULAR_COURSES_CACHE = "popularCourses";
    private static final String COURSE_LIST_ITEM_CACHE = "courseListItem";
    private static final String ALL_THEME_CACHE_KEY = "ALL";
    private static final String RANKING_LOCK_KEY_PREFIX = "lock:popularCourses:";
    private static final Duration RANKING_LOCK_TTL = Duration.ofSeconds(5);
    // 재시도 예산(10 * 100ms = 1초)은 락 TTL(5초)보다 짧게 잡아 승자가 죽어 락이 자연 만료되는
    // 경우에도 그 안에서 폴백이 끝나도록 한다. 처음엔 3 * 50ms = 150ms였는데, 벤치마크용으로
    // 강제로 채운 50만 건(콜드 조회 1.7~2.5초) 기준으로는 재시도 창이 승자의 작업 시간보다
    // 훨씬 짧아 나머지 요청들이 승자를 기다리지 않고 각자 DB로 직행해버리는 문제가 있었다.
    // 다만 50만 건은 실제 데이터가 아니라 순전히 벤치마크를 위해 넣은 극단치이고, 현재 실제
    // 데이터 규모는 그 근처도 아니라서 그 극단치에 맞춰 4초까지 늘릴 필요는 없다고 판단해
    // 1초로 절충했다. 실제 데이터가 크게 늘거나(수십만 건 이상) view_count 인덱스 추가 전까지는
    // 이 값을 실제 운영 데이터 규모에 맞춰 재조정해야 한다 — 근본 해결책은 재시도 시간을 늘리는
    // 게 아니라 랭킹 쿼리 자체를 인덱스로 빠르게 만드는 것이다.
    private static final int RANKING_LOCK_RETRY_COUNT = 10;
    private static final long RANKING_LOCK_RETRY_INTERVAL_MS = 100;

    // 락 소유자만 자기 락을 지울 수 있도록 SET/DEL을 원자적으로 비교-삭제하는 스크립트.
    // 단순 DEL이면 TTL 만료 후 다른 요청이 이미 잡은 락을 실수로 지울 위험이 있다.
    private static final RedisScript<Long> UNLOCK_SCRIPT = RedisScript.of("""
        if redis.call('get', KEYS[1]) == ARGV[1] then
            return redis.call('del', KEYS[1])
        else
            return 0
        end
        """, Long.class);

    private final UploadCourseRepository uploadCourseRepository;
    private final MyCourseService myCourseService;
    private final UserService userService;
    private final S3Service s3Service;
    private final CacheManager cacheManager;
    private final RedisTemplate<String, String> redisTemplate;
    private final RedisTemplate<String, Object> cacheValueRedisTemplate;

    @Override
    @Transactional(readOnly = true)
    public CourseKeywordListResponse getCourseKeywordList() {
        return UploadCourseMapper.toKeywordListResponse();
    }

    @Override
    @Transactional
    public UploadCourseCreateResponse createUploadCourse(UploadCourseCreateRequest request,
        MultipartFile thumbnailImage) {
        // 원본 소유권 검증 + 중복 업로드 체크 + 원본과 독립된 사본(TravelCourse) 딥카피를 한 번에 수행.
        // 이 호출이 실패하면(소유권 없음/이미 업로드됨) 아래 S3 업로드를 아예 시도하지 않는다(fail-fast).
        TravelCourse hiddenCopy = myCourseService.createHiddenUploadCopy(request.myCourseId());

        User user = userService.getUser(userService.getCurrentUserId());

        String thumbnailS3Key = null;
        if (thumbnailImage != null) {
            try {
                thumbnailS3Key = s3Service.uploadFile(thumbnailImage).key();
            } catch (IOException e) {
                throw new BusinessException(S3ErrorCode.FAIL_UPLOAD_FILE);
            }
        } else {
            thumbnailS3Key = "default-upload-course-thumbnail.png";
        }

        UploadCourse savedUploadCourse = uploadCourseRepository.save(
            UploadCourseMapper.toEntity(request, hiddenCopy, user, thumbnailS3Key));

        //업로드 코스에 키워드 연동
        for (KeywordType keyword : request.keywords()) {
            savedUploadCourse.getKeywords().add(new CourseKeyword(savedUploadCourse, keyword));
        }

        List<DayScheduleResponse> daySchedules = myCourseService.getAllDaySchedulesByOwnedCourse(
            hiddenCopy.getId());

        return UploadCourseMapper.toCreateResponse(savedUploadCourse, hiddenCopy, daySchedules);
    }

    @Override
    @Transactional(readOnly = true)
    public UploadCourseDetailResponse getDetail(Long uploadCourseId) {
        UploadCourse uploadCourse = uploadCourseRepository.findWithTravelCourseAndKeywords(
                uploadCourseId)
            .orElseThrow(
                () -> new BusinessException(UploadCourseErrorCode.UPLOAD_COURSE_NOT_FOUND));

        uploadCourse.increaseViewCount(); //조회 수 증가

        List<DayScheduleResponse> daySchedules = myCourseService.getAllDaySchedulesByCourse(
            uploadCourse.getTravelCourse().getId());

        return UploadCourseMapper.toDetailResponse(uploadCourse, getGetThumbnailUrl(
            uploadCourse), daySchedules);
    }

    @Override
    @Transactional(readOnly = true)
    public UploadCourseListResponse getAllForSearch(String keyword, List<KeywordType> tags,
        UploadCourseSortType sortType) {
        if (tags == null) {
            tags = List.of();
        }

        String pattern = (keyword == null || keyword.isBlank())
            ? null
            : "%" + keyword + "%";

        List<UploadCourse> uploadCourses = switch (sortType) {
            case NEW -> uploadCourseRepository.findAllByKeywordsOrderByCreatedAtDesc(pattern, tags,
                tags.size());
            case POPULAR ->
                uploadCourseRepository.findAllByKeywordsOrderByViewCountDesc(pattern, tags,
                    tags.size());
        };

        return new UploadCourseListResponse(uploadCourses.stream()
            .map(uploadCourse ->
                UploadCourseMapper.toListItemResponse(uploadCourse,
                    getGetThumbnailUrl(uploadCourse))
            )
            .toList()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public UploadCourseListResponse getMyUploadCourses() {
        Long userId = userService.getCurrentUserId();
        List<UploadCourse> uploadCourses = uploadCourseRepository.findAllByUserIdOrderByCreatedAtDesc(
            userId);

        return new UploadCourseListResponse(uploadCourses.stream()
            .map(uploadCourse ->
                UploadCourseMapper.toListItemResponse(uploadCourse,
                    getGetThumbnailUrl(uploadCourse))
            )
            .toList()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public UploadCourseListResponse getPopularCourses(KeywordType theme) {
        validateThemeIsMoodOrNull(theme);

        List<Long> ids = getPopularCourseIds(theme);
        List<CourseListItemCacheItem> items = getCourseListItems(ids);

        return new UploadCourseListResponse(items.stream()
            .map(item -> UploadCourseMapper.toListItemResponse(item,
                getThumbnailUrlFromCacheItem(item)))
            .toList()
        );
    }

    // ==========================
    //  1단계: 랭킹 캐시 (테마별 top5 코스ID)
    // ==========================

    private List<Long> getPopularCourseIds(KeywordType theme) {
        String cacheKey = theme != null ? theme.name() : ALL_THEME_CACHE_KEY;

        List<Long> cachedIds = readRankingCache(cacheKey);
        if (cachedIds != null) {
            return cachedIds;
        }

        return computePopularCourseIdsWithLock(theme, cacheKey);
    }

    /**
     * 랭킹 캐시 미스(콜드 스타트/TTL 만료) 시 콜드 스타트 스탬피드를 막기 위한 분산 락 경로.
     * 락을 획득한 요청만 무거운 랭킹 쿼리를 실행하고, 나머지는 짧게 재시도하며 캐시가 채워지길 기다린다.
     */
    private List<Long> computePopularCourseIdsWithLock(KeywordType theme, String cacheKey) {
        String lockKey = RANKING_LOCK_KEY_PREFIX + cacheKey;
        String lockToken = UUID.randomUUID().toString();

        Boolean locked = tryAcquireRankingLock(lockKey, lockToken);

        if (locked == null) {
            // Redis 자체가 예외를 던진 경우 — 락 재시도 없이 바로 DB 직접 조회로 폴백(fail-open)
            return uploadCourseRepository.findPopularCourseIds(theme, PageRequest.of(0, 5));
        }

        if (locked) {
            try {
                List<Long> ids = uploadCourseRepository.findPopularCourseIds(theme,
                    PageRequest.of(0, 5));
                writeRankingCache(cacheKey, ids);
                return ids;
            } finally {
                releaseRankingLock(lockKey, lockToken);
            }
        }

        // 락 획득 실패 — 다른 요청이 이미 채우는 중이므로 짧게 재시도하며 캐시를 재확인한다
        for (int i = 0; i < RANKING_LOCK_RETRY_COUNT; i++) {
            sleepQuietly(RANKING_LOCK_RETRY_INTERVAL_MS);
            List<Long> retried = readRankingCache(cacheKey);
            if (retried != null) {
                return retried;
            }
        }

        // 재시도로도 채워지지 않으면 캐싱 없이 DB 직접 조회로 최종 폴백
        return uploadCourseRepository.findPopularCourseIds(theme, PageRequest.of(0, 5));
    }

    /**
     * @return true(락 획득 성공), false(다른 요청이 이미 보유 중), null(Redis 예외 — fail-open 대상)
     */
    private Boolean tryAcquireRankingLock(String lockKey, String lockToken) {
        try {
            return redisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, RANKING_LOCK_TTL);
        } catch (Exception e) {
            log.warn("랭킹 캐시 분산 락 획득 시도 실패, DB로 폴백합니다. lockKey={}", lockKey, e);
            return null;
        }
    }

    private void releaseRankingLock(String lockKey, String lockToken) {
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), lockToken);
        } catch (Exception e) {
            // fail-open: 해제에 실패해도 TTL(5초)이 지나면 자동 만료되므로 서비스에는 영향 없다
            log.warn("랭킹 캐시 분산 락 해제 실패(TTL로 자동 만료됨). lockKey={}", lockKey, e);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Long> readRankingCache(String cacheKey) {
        try {
            Cache cache = cacheManager.getCache(POPULAR_COURSES_CACHE);
            if (cache == null) {
                return null;
            }
            // GenericJackson2JsonRedisSerializer는 List<Long>을 역직렬화할 때 값 범위에 따라
            // Integer로 복원하는 경우가 있어(제네릭 소거로 요소 타입 정보가 없음), Number로 안전하게 받아 Long으로 변환한다.
            List<Number> raw = cache.get(cacheKey, List.class);
            return raw == null ? null : raw.stream().map(Number::longValue).toList();
        } catch (Exception e) {
            // fail-open: Redis 장애/지연 시 캐시가 없는 것으로 취급하고 DB 조회로 폴백한다
            log.warn("랭킹 캐시 조회 실패, DB로 폴백합니다. cacheKey={}", cacheKey, e);
            return null;
        }
    }

    private void writeRankingCache(String cacheKey, List<Long> ids) {
        try {
            Cache cache = cacheManager.getCache(POPULAR_COURSES_CACHE);
            if (cache != null) {
                cache.put(cacheKey, ids);
            }
        } catch (Exception e) {
            // fail-open: 캐시 저장에 실패해도 이미 계산된 응답은 정상 반환한다
            log.warn("랭킹 캐시 저장 실패. cacheKey={}", cacheKey, e);
        }
    }

    // ==========================
    //  2단계: 아이템 캐시 (코스ID별 콘텐츠, 범용 캐시)
    // ==========================

    private List<CourseListItemCacheItem> getCourseListItems(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }

        Map<Long, CourseListItemCacheItem> itemsById = readItemCache(ids);

        List<Long> missingIds = ids.stream()
            .filter(id -> !itemsById.containsKey(id))
            .toList();

        if (!missingIds.isEmpty()) {
            List<UploadCourse> uploadCourses = uploadCourseRepository.findAllByIdInWithKeywords(
                missingIds);
            Map<Long, CourseListItemCacheItem> newItems = new HashMap<>();
            for (UploadCourse uploadCourse : uploadCourses) {
                CourseListItemCacheItem item = UploadCourseMapper.toCourseListItemCacheItem(
                    uploadCourse);
                itemsById.put(uploadCourse.getId(), item);
                newItems.put(uploadCourse.getId(), item);
            }
            // 개별 SET을 코스 개수만큼 반복하면 Redis 장애 시 타임아웃이 그만큼 곱해지는 문제가
            // 실측(fail-open 확인 중)으로 드러나, 파이프라인으로 한 번의 왕복에 모아 보낸다.
            writeItemCacheBatch(newItems);
        }

        // IN 조회는 순서를 보장하지 않으므로 1단계에서 얻은 순위 순서로 재정렬
        // (두 쿼리 사이 삭제된 코스가 있으면 그 ID는 자연히 제외된다)
        return ids.stream()
            .map(itemsById::get)
            .filter(Objects::nonNull)
            .toList();
    }

    private Map<Long, CourseListItemCacheItem> readItemCache(List<Long> ids) {
        Map<Long, CourseListItemCacheItem> result = new HashMap<>();
        try {
            // 코스 개수(최대 5건)만큼 개별 GET을 반복하면 왕복이 그만큼 누적돼 벤치마크에서
            // 실측으로 확인된 지연 문제였다 — MGET으로 한 번에 배치 조회해 왕복을 1회로 줄인다.
            List<String> keys = ids.stream()
                .map(id -> COURSE_LIST_ITEM_CACHE + "::" + id)
                .toList();
            List<Object> values = cacheValueRedisTemplate.opsForValue().multiGet(keys);
            if (values == null) {
                return result;
            }
            for (int i = 0; i < ids.size(); i++) {
                if (values.get(i) instanceof CourseListItemCacheItem item) {
                    result.put(ids.get(i), item);
                }
            }
        } catch (Exception e) {
            // fail-open: 캐시 조회 실패 시 전부 미스로 취급하고 DB 배치 조회로 폴백한다
            log.warn("아이템 캐시 조회 실패, DB로 폴백합니다.", e);
            result.clear();
        }
        return result;
    }

    /**
     * 콘텐츠 변경 이벤트(fork로 인한 forkCount 증가 등) 발생 시 코스 1건만 즉시 write-through할 때 사용한다.
     * 1건짜리 쓰기는 파이프라인으로 묶을 대상이 없어 CacheManager를 그대로 쓴다.
     */
    private void writeItemCache(Long uploadCourseId, CourseListItemCacheItem item) {
        try {
            Cache cache = cacheManager.getCache(COURSE_LIST_ITEM_CACHE);
            if (cache != null) {
                cache.put(uploadCourseId.toString(), item);
            }
        } catch (Exception e) {
            // fail-open: 캐시 저장 실패해도 이미 조회된 응답은 정상 반환한다
            log.warn("아이템 캐시 저장 실패. uploadCourseId={}", uploadCourseId, e);
        }
    }

    /**
     * 콜드 미스로 여러 건을 한꺼번에 채울 때 사용하는 배치 저장.
     * Redis의 MSET은 키별 TTL을 지정할 수 없어, 파이프라인으로 SET(+TTL)을 여러 건 묶어 왕복 1회에 보낸다.
     * 개별 SET을 반복하면 정상 상황에서도 왕복이 누적되고, Redis 장애 시에는 타임아웃(1초)이 건수만큼
     * 곱해져 응답이 수 초 단위로 늘어지는 문제가 실측으로 확인됐다.
     */
    @SuppressWarnings("unchecked")
    private void writeItemCacheBatch(Map<Long, CourseListItemCacheItem> items) {
        if (items.isEmpty()) {
            return;
        }
        try {
            RedisSerializer<String> keySerializer =
                (RedisSerializer<String>) cacheValueRedisTemplate.getKeySerializer();
            RedisSerializer<Object> valueSerializer =
                (RedisSerializer<Object>) cacheValueRedisTemplate.getValueSerializer();
            long ttlSeconds = RedisConfig.COURSE_LIST_ITEM_TTL.getSeconds();

            cacheValueRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Map.Entry<Long, CourseListItemCacheItem> entry : items.entrySet()) {
                    byte[] key = keySerializer.serialize(
                        COURSE_LIST_ITEM_CACHE + "::" + entry.getKey());
                    byte[] value = valueSerializer.serialize(entry.getValue());
                    connection.stringCommands()
                        .set(key, value, Expiration.seconds(ttlSeconds), SetOption.upsert());
                }
                return null;
            });
        } catch (Exception e) {
            // fail-open: 캐시 저장 실패해도 이미 조회된 응답은 정상 반환한다
            log.warn("아이템 캐시 배치 저장 실패. ids={}", items.keySet(), e);
        }
    }

    private String getThumbnailUrlFromCacheItem(CourseListItemCacheItem item) {
        if (item.thumbnailImageS3Key() == null) {
            return null;
        }
        return s3Service.getPresignedUrl(item.thumbnailImageS3Key());
    }

    private void validateThemeIsMoodOrNull(KeywordType theme) {
        if (theme != null && !KeywordType.findByCategory("mood").contains(theme)) {
            throw new BusinessException(UploadCourseErrorCode.INVALID_THEME_TYPE);
        }
    }

    private String getGetThumbnailUrl(UploadCourse uploadCourse) {
        String thumbnailUrl = null;
        if (uploadCourse.getThumbnailImageS3Key() != null) {
            thumbnailUrl = s3Service.getPresignedUrl(
                uploadCourse.getThumbnailImageS3Key());//썸네일 프리사인드 URL 생성
        }
        return thumbnailUrl;
    }

    @Override
    @Transactional
    public UploadCourseDetailResponse updateUploadCourse(Long uploadCourseId,
        UploadCourseUpdateRequest request, MultipartFile thumbnailImage,
        List<MultipartFile> placeImages) {
        UploadCourse uploadCourse = uploadCourseRepository.findWithTravelCourseAndKeywords(uploadCourseId)
            .orElseThrow(() -> new BusinessException(UploadCourseErrorCode.UPLOAD_COURSE_NOT_FOUND));

        Long currentUserId = userService.getCurrentUserId();
        if (!uploadCourse.getUser().getId().equals(currentUserId)) {
            throw new BusinessException(UploadCourseErrorCode.NOT_OWNED_UPLOAD_COURSE);
        }

        // 1. 썸네일 이미지 업데이트 (신규 이미지 첨부 시 기존 S3 파일 삭제 후 저장)
        String newThumbnailS3Key = uploadCourse.getThumbnailImageS3Key();
        if (thumbnailImage != null && !thumbnailImage.isEmpty()) {
            if (newThumbnailS3Key != null && !"default-upload-course-thumbnail.png".equals(newThumbnailS3Key)) {
                s3Service.deleteFile(newThumbnailS3Key);
            }
            try {
                newThumbnailS3Key = s3Service.uploadFile(thumbnailImage).key();
            } catch (IOException e) {
                throw new BusinessException(S3ErrorCode.FAIL_UPLOAD_FILE);
            }
        }

        // 2. UploadCourse 정보 및 키워드 갱신
        uploadCourse.updateUploadCourseInfo(request.title(), request.introduction(), request.location(), newThumbnailS3Key);

        uploadCourse.getKeywords().clear();
        if (request.keywords() != null) {
            for (KeywordType keyword : request.keywords()) {
                uploadCourse.getKeywords().add(new CourseKeyword(uploadCourse, keyword));
            }
        }

        // 3. TravelCourse 정보 갱신
        TravelCourse travelCourse = uploadCourse.getTravelCourse();
        travelCourse.updateCourseInfo(request.title(), request.location(), request.startDate(), request.endDate());

        // 4. DaySchedule, Place, PlaceImage 매칭 및 동기화 (갱신/추가/삭제)
        List<DaySchedule> existingDaySchedules = myCourseService.getDaySchedulesWithPlaces(travelCourse.getId());

        Map<Long, DaySchedule> existingDayMap = existingDaySchedules.stream()
            .collect(Collectors.toMap(DaySchedule::getId, Function.identity()));

        Map<Long, Place> existingPlaceMap = existingDaySchedules.stream()
            .flatMap(ds -> ds.getPlaces().stream())
            .collect(Collectors.toMap(Place::getId, Function.identity()));

        Map<Long, PlaceImage> existingImageMap = existingDaySchedules.stream()
            .flatMap(ds -> ds.getPlaces().stream())
            .flatMap(p -> p.getPlaceImages().stream())
            .collect(Collectors.toMap(PlaceImage::getId, Function.identity()));

        Set<Long> requestedDayIds = new HashSet<>();
        Set<Long> requestedPlaceIds = new HashSet<>();
        Set<Long> requestedImageIds = new HashSet<>();

        if (request.daySchedules() != null) {
            for (DayScheduleUpdateRequest dayDto : request.daySchedules()) {
                DaySchedule daySchedule;
                if (dayDto.dayScheduleId() != null && existingDayMap.containsKey(dayDto.dayScheduleId())) {
                    daySchedule = existingDayMap.get(dayDto.dayScheduleId());
                    requestedDayIds.add(daySchedule.getId());
                } else {
                    daySchedule = new DaySchedule(travelCourse, dayDto.day());
                    travelCourse.getDaySchedules().add(daySchedule);
                }

                if (dayDto.places() != null) {
                    for (PlaceUpdateRequest placeDto : dayDto.places()) {
                        Place place;
                        if (placeDto.placeId() != null && existingPlaceMap.containsKey(placeDto.placeId())) {
                            place = existingPlaceMap.get(placeDto.placeId());
                            place.updatePlaceInfo(placeDto.placeName(), placeDto.startTime(), placeDto.memo(),
                                placeDto.latitude(), placeDto.longitude(), placeDto.placeUrl(), placeDto.placeLocation());
                            requestedPlaceIds.add(place.getId());
                        } else {
                            place = Place.builder()
                                .daySchedule(daySchedule)
                                .placeName(placeDto.placeName())
                                .startTime(placeDto.startTime())
                                .memo(placeDto.memo())
                                .latitude(placeDto.latitude())
                                .longitude(placeDto.longitude())
                                .placeUrl(placeDto.placeUrl())
                                .placeLocation(placeDto.placeLocation())
                                .build();
                            daySchedule.getPlaces().add(place);
                        }

                        if (placeDto.placeImages() != null) {
                            for (PlaceImageUpdateRequest imgDto : placeDto.placeImages()) {
                                if (imgDto.placeImageId() != null && existingImageMap.containsKey(imgDto.placeImageId())) {
                                    requestedImageIds.add(imgDto.placeImageId());
                                } else if (imgDto.newImageIndex() != null && placeImages != null
                                    && imgDto.newImageIndex() >= 0 && imgDto.newImageIndex() < placeImages.size()) {
                                    MultipartFile newFile = placeImages.get(imgDto.newImageIndex());
                                    if (newFile != null && !newFile.isEmpty()) {
                                        try {
                                            String imageS3Key = s3Service.uploadFile(newFile).key();
                                            place.getPlaceImages().add(new PlaceImage(place, imageS3Key));
                                        } catch (IOException e) {
                                            throw new BusinessException(S3ErrorCode.FAIL_UPLOAD_FILE);
                                        }
                                    }
                                }
                            }
                        }

                        // 요청에 포함되지 않은 기존 장소 이미지 삭제 (S3 및 DB)
                        List<PlaceImage> imagesToRemove = place.getPlaceImages().stream()
                            .filter(img -> img.getId() != null && !requestedImageIds.contains(img.getId()))
                            .toList();
                        for (PlaceImage img : imagesToRemove) {
                            s3Service.deleteFile(img.getPlaceImageS3Key());
                            place.getPlaceImages().remove(img);
                        }
                    }
                }

                // 요청에 포함되지 않은 기존 장소 삭제 (S3 및 DB)
                List<Place> placesToRemove = daySchedule.getPlaces().stream()
                    .filter(p -> p.getId() != null && !requestedPlaceIds.contains(p.getId()))
                    .toList();
                for (Place p : placesToRemove) {
                    p.getPlaceImages().forEach(img -> s3Service.deleteFile(img.getPlaceImageS3Key()));
                    daySchedule.getPlaces().remove(p);
                }
            }
        }

        // 요청에 포함되지 않은 기존 일차 삭제 (S3 및 DB)
        List<DaySchedule> daysToRemove = travelCourse.getDaySchedules().stream()
            .filter(ds -> ds.getId() != null && !requestedDayIds.contains(ds.getId()))
            .toList();
        for (DaySchedule ds : daysToRemove) {
            ds.getPlaces().forEach(p -> p.getPlaceImages().forEach(img -> s3Service.deleteFile(img.getPlaceImageS3Key())));
            travelCourse.getDaySchedules().remove(ds);
        }

        List<DayScheduleResponse> updatedDaySchedules = myCourseService.getAllDaySchedulesByCourse(travelCourse.getId());
        return UploadCourseMapper.toDetailResponse(uploadCourse, getGetThumbnailUrl(uploadCourse), updatedDaySchedules);
    }
}
