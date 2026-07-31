# API 변경사항 — 인기 코스 상위 5개 조회

> 이 문서는 프론트엔드(Android) 담당자/에이전트에게 전달하기 위한 API 변경사항 요약입니다. **기존 API는 하나도 변경되지 않았고, 신규 엔드포인트 1개만 추가**되었습니다.

## 요약

| 항목 | 내용 |
|---|---|
| 변경 종류 | 신규 엔드포인트 추가 (기존 엔드포인트 변경 없음, breaking change 없음) |
| 엔드포인트 | `GET /api/upload-courses/popular` |
| 인증 | **불필요** (비로그인 상태에서도 호출 가능 — `permitAll`) |
| 대응 화면 | 홈 화면의 "요즘 뜨는 인기 코스"(전체) + "테마별 인기 코스"(테마 탭) 두 섹션 |

## 엔드포인트 상세

### `GET /api/upload-courses/popular`

조회수(viewCount) 기준 상위 **최대 5개**만 반환합니다. 페이지네이션은 지원하지 않습니다(더보기/다음 페이지 없음).

#### 쿼리 파라미터

| 이름 | 타입 | 필수 여부 | 설명 |
|---|---|---|---|
| `theme` | string (enum) | 선택 | 테마별 인기 코스 조회 시 사용. 아래 "허용되는 theme 값" 표의 코드만 전달 가능 |

- `theme`을 **생략**하면 → 전체 코스 기준 인기 top5 (홈 화면 "요즘 뜨는 인기 코스" 섹션에 대응)
- `theme`을 **포함**하면 → 해당 테마(mood)에 속한 코스 중 인기 top5 (홈 화면 "테마별 인기 코스" 섹션의 각 탭에 대응)

#### 허용되는 `theme` 값 (mood 카테고리 전용)

기존 `GET /api/upload-courses/keywords`(코스 키워드 목록 조회 API)에서 반환하는 `mood` 카테고리의 `code` 값과 **완전히 동일**합니다. 홈 화면 테마 탭 UI가 그 순서 그대로라면 아래 7개 탭 = 아래 7개 코드입니다.

| 코드 (`code`, 이 API에 넘길 값) | 라벨 (화면 표시용) |
|---|---|
| `HEALING` | 힐링 |
| `ACTIVITY` | 액티비티 |
| `FOOD` | 맛집탐방 |
| `SENSIBILITY` | 감성 |
| `CULTURE` | 문화/전시 |
| `NATURE` | 자연 |
| `SHOPPING` | 쇼핑 |

**주의**: 이 7개 외의 코드(예: `WALK`, `CAR`, `SOLO`, `ONE_DAY`, `COST_EFFECTIVE` 등 — travelMode/companionType/duration/budget 카테고리)를 `theme`으로 전달하면 **400 에러**가 발생합니다. 홈 화면 테마 탭은 mood 카테고리 값만 쓰므로 정상 사용 시에는 발생하지 않지만, 다른 화면의 필터 값을 실수로 재사용하지 않도록 주의가 필요합니다.

#### 요청 예시

```
GET /api/upload-courses/popular
GET /api/upload-courses/popular?theme=FOOD
GET /api/upload-courses/popular?theme=HEALING
```

#### 응답 스키마

기존 `GET /api/upload-courses`(업로드 코스 목록 조회)의 응답과 **완전히 동일한 스키마**(`UploadCourseListResponse`)입니다. 이미 그 API의 응답을 파싱하는 모델/DTO가 있다면 그대로 재사용 가능합니다.

```json
{
  "uploadCourses": [
    {
      "uploadCourseId": 3,
      "title": "대전 맛도리 빵집 투어",
      "location": "대전 유성구, 중구",
      "thumbnailImageUrl": "https://.../thumbnail.jpg?X-Amz-...",
      "forkCount": 112,
      "keywords": ["뚜벅이", "맛집탐방", "쇼핑", "가성비"]
    },
    {
      "uploadCourseId": 17,
      "title": "여의도 한강공원 근처 나들이",
      "location": "서울 여의도",
      "thumbnailImageUrl": "https://.../thumbnail2.jpg?X-Amz-...",
      "forkCount": 87,
      "keywords": ["자차", "연인", "힐링", "자연"]
    }
  ]
}
```

- `uploadCourses` 배열은 인기순(조회수 내림차순)으로 정렬되어 있으며, **항상 0~5개**입니다(코스 개수가 5개 미만이면 그만큼만, 5개 이상이어도 최대 5개).
- `thumbnailImageUrl`은 **15분짜리 임시 URL(presigned URL)**입니다. 기존 목록/상세 API와 동일한 정책이므로, 캐싱해서 오래 재사용하지 말고 화면 진입/새로고침 시마다 새로 받은 값을 써야 합니다.
- `keywords`는 라벨 문자열 배열입니다(코드가 아님) — 화면에 태그 칩으로 그대로 표시하면 됩니다.

#### 에러 응답

| 상황 | HTTP 상태 | `code` | `message` |
|---|---|---|---|
| `theme`이 mood 카테고리가 아닌 값 | 400 | `INVALID_THEME_TYPE` | 올바르지 않은 테마입니다. |

```json
{
  "timestamp": "2026-07-31T15:33:16.7245397",
  "code": "INVALID_THEME_TYPE",
  "message": "올바르지 않은 테마입니다."
}
```

이 프로젝트는 공통 응답 래퍼가 없고, 에러 시 위 형태(`timestamp`/`code`/`message`)의 JSON을 그대로 반환합니다(다른 API 에러와 동일한 포맷).

## 화면 매핑 가이드

| UI 요소 | 호출 방식 |
|---|---|
| "요즘 뜨는 인기 코스" 섹션 (홈 상단, 테마 필터 없음) | `GET /api/upload-courses/popular` (theme 생략) |
| "테마별 인기 코스" 섹션의 각 탭(힐링/액티비티/맛집탐방/감성/문화·전시/자연/쇼핑) | 선택된 탭에 해당하는 코드로 `GET /api/upload-courses/popular?theme={code}` |
| "인기 코스 더 보기" / "테마별 코스 더 보기" 버튼 | **이 API가 아니라** 기존 `GET /api/upload-courses?sort=POPULAR`(+ 필요 시 `tag=`)를 사용해야 합니다. 이번 API는 홈 화면용 top5 전용이며 페이지네이션이 없어 "더 보기" 흐름에는 맞지 않습니다. |

## 참고 — 이번 변경에 포함되지 않은 것

- 기존 `GET /api/upload-courses`(키워드/태그 검색), `GET /api/upload-courses/{id}`(상세), `GET /api/upload-courses/me`(내 코스) 등은 **일절 변경되지 않았습니다.**
- 서버 내부적으로 이 신규 엔드포인트에 Redis 캐싱을 적용하는 작업이 진행 중이지만, 이는 응답 스키마·동작에 영향을 주지 않는 서버 내부 최적화이므로 FE에서 신경 쓸 부분이 없습니다.
