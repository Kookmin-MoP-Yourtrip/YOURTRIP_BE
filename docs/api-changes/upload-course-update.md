# API 변경사항 — 업로드 코스 통합 수정

> 이 문서는 프론트엔드(Android) 개발자에게 전달하기 위한 API 변경사항 문서입니다. **기존 API에는 영향이 없으며, 업로드 코스를 한 번에 수정할 수 있는 단일 신규 엔드포인트가 추가**되었습니다.

---

## 1. 요약

| 항목 | 내용 |
|---|---|
| **변경 종류** | 신규 엔드포인트 추가 (기존 API 변경 없음, Breaking Change 없음) |
| **엔드포인트** | `PUT /api/upload-courses/{uploadCourseId}` |
| **Request Type** | `multipart/form-data` |
| **인증 여부** | **필수** (`Authorization: Bearer <JWT_TOKEN>`, 작성자 본인만 가능) |
| **대응 화면** | 업로드한 코스 상세 화면 → **코스 수정 화면** |

---

## 2. 엔드포인트 상세

### `PUT /api/upload-courses/{uploadCourseId}`

업로드 코스의 주요 정보(제목, 소개, 위치, 여행 기간, 키워드, 썸네일 이미지, 일차별 일정 및 장소 사진)를 단일 API로 통합 수정합니다.

> [!NOTE]
> `forkCount`, `viewCount`, `heartCount` 및 PK ID값(`uploadCourseId`, `dayScheduleId`, `placeId`, `placeImageId`) 자체는 절대 변경할 수 없으나, 기존 장소/일정 엔티티를 식별하고 유지·갱신하기 위해 DTO 요청에 해당 PK ID를 포함하여 전달해야 합니다.

---

### 3. Request Parts (`multipart/form-data`)

| Part Name | Content-Type | 필수 여부 | 설명 |
|---|---|---|---|
| `request` | `application/json` | **필수** | 수정할 코스 전체 데이터 (`UploadCourseUpdateRequest` JSON) |
| `thumbnailImage` | `image/*` | 선택 | 신규 썸네일 이미지 파일. **미전달(null) 시 기존 썸네일 이미지가 그대로 유지**됩니다. |
| `placeImages` | `image/*` (List) | 선택 | 장소에 새로 추가/교체할 이미지 파일 목록. DTO의 `newImageIndex`와 0부터 시작하는 인덱스로 매핑됩니다. |

---

## 4. DTO 스키마 (`request` JSON)

### 4.1 Root: `UploadCourseUpdateRequest`

| 필드명 | 타입 | 필수 여부 | 설명 |
|---|---|---|---|
| `title` | string | **필수** | 코스 제목 (비어있을 수 없음) |
| `introduction` | string | 선택 | 코스 소개글 |
| `location` | string | 선택 | 대표 여행 위치 (예: `"경주 포석로"`) |
| `startDate` | string (ISO Date) | **필수** | 여행 시작일 (`"YYYY-MM-DD"`) |
| `endDate` | string (ISO Date) | **필수** | 여행 종료일 (`"YYYY-MM-DD"`) |
| `keywords` | Array\<KeywordType\> | 선택 | 코스 태그/키워드 enum 코드 목록 (예: `["WALK", "FOOD"]`) |
| `daySchedules` | Array\<DayScheduleUpdateRequest\> | 선택 | 일차별 일정 목록 |

---

### 4.2 `DayScheduleUpdateRequest`

| 필드명 | 타입 | 필수 여부 | 설명 |
|---|---|---|---|
| `dayScheduleId` | Long | 선택 | **기존 일차 PK**. 기존일정을 수정하는 경우 필수 전달. **신규 생성 시 `null`** |
| `day` | Integer | **필수** | 몇 일차인지 지정 (1부터 시작) |
| `places` | Array\<PlaceUpdateRequest\> | 선택 | 해당 일차에 포함된 장소 목록 |

---

### 4.3 `PlaceUpdateRequest`

| 필드명 | 타입 | 필수 여부 | 설명 |
|---|---|---|---|
| `placeId` | Long | 선택 | **기존 장소 PK**. 기존 장소를 수정하는 경우 필수 전달. **신규 생성 시 `null`** |
| `placeName` | string | **필수** | 장소명 |
| `startTime` | string (ISO Time) | 선택 | 방문 시작 시간 (`"HH:mm"` 또는 `"HH:mm:ss"`) |
| `memo` | string | 선택 | 장소 메모 |
| `latitude` | double | **필수** | 위도 |
| `longitude` | double | **필수** | 경도 |
| `placeUrl` | string | 선택 | 카카오 지도 장소 URL |
| `placeLocation` | string | 선택 | 장소 주소 |
| `placeImages` | Array\<PlaceImageUpdateRequest\> | 선택 | 장소 첨부 사진 목록 |

---

### 4.4 `PlaceImageUpdateRequest`

| 필드명 | 타입 | 필수 여부 | 설명 |
|---|---|---|---|
| `placeImageId` | Long | 선택 | **기존 장소 사진 PK**. 기존 사진을 그대로 유지할 때 전달. 신규 사진 첨부 시 `null` |
| `newImageIndex` | Integer | 선택 | **신규 첨부 사진 인덱스**. `placeImageId`가 `null`일 때 `placeImages` 파일 리스트의 몇 번째 파일(0부터 시작)인지를 지정. 기존 사진 유지 시 `null` |

---

## 5. 엔티티 수정/추가/삭제 매칭 규칙 (중요 ⭐)

프론트엔드에서는 요청 DTO 구성 시 아래 규칙을 반드시 준수해야 합니다.

1. **기존 항목 유지 및 갱신 (ID 전달)**:
   - DTO에 `dayScheduleId`, `placeId`, `placeImageId` 값을 포함하여 전송하면, 해당 엔티티의 정보가 전달받은 값으로 갱신됩니다.
2. **신규 항목 추가 (ID = null)**:
   - `dayScheduleId` 또는 `placeId`를 `null`로 전송하면 신규 일차/장소로 DB에 추가됩니다.
   - 장소 사진의 경우 `placeImageId: null`로 설정하고 `newImageIndex`에 `placeImages` 파트 파일 리스트에서의 0 기반 인덱스(`0`, `1`, `2`...)를 지정합니다.
3. **기존 항목 삭제 (ID 누락)**:
   - 기존 DB에 존재하던 일차, 장소, 사진 ID 중 **이번 수정 DTO 목록에서 빠진 항목은 DB에서 자동으로 삭제** 처리됩니다 (연관된 S3 이미지 파일도 자동 삭제됨).

---

## 6. 요청 예시

### 6.1 `request` JSON 예시 (`application/json`)

```json
{
  "title": "경주 인생샷 투어 (수정)",
  "introduction": "황리단길과 첨성대 야경 코스를 일부분 수정했습니다.",
  "location": "경주 포석로",
  "startDate": "2025-03-01",
  "endDate": "2025-03-02",
  "keywords": ["WALK", "FOOD", "HEALING"],
  "daySchedules": [
    {
      "dayScheduleId": 100,
      "day": 1,
      "places": [
        {
          "placeId": 200,
          "placeName": "황리단길 카페거리",
          "startTime": "10:30:00",
          "memo": "카페 골목 산책 및 브런치",
          "latitude": 35.8375,
          "longitude": 129.2123,
          "placeUrl": "http://place.map.kakao.com/26338954",
          "placeLocation": "경북 경주시 포석로 인근",
          "placeImages": [
            {
              "placeImageId": 500,
              "newImageIndex": null
            },
            {
              "placeImageId": null,
              "newImageIndex": 0
            }
          ]
        }
      ]
    },
    {
      "dayScheduleId": null,
      "day": 2,
      "places": [
        {
          "placeId": null,
          "placeName": "불국사",
          "startTime": "09:00:00",
          "memo": "아침 산책",
          "latitude": 35.7901,
          "longitude": 129.3323,
          "placeUrl": "http://place.map.kakao.com/123456",
          "placeLocation": "경북 경주시 불국로 385",
          "placeImages": [
            {
              "placeImageId": null,
              "newImageIndex": 1
            }
          ]
        }
      ]
    }
  ]
}
```

---

## 7. 응답 스키마 (`200 OK`)

기존 `GET /api/upload-courses/{uploadCourseId}` (상세 조회) 응답 스키마(`UploadCourseDetailResponse`)와 **완전히 동일**합니다.

```json
{
  "uploadCourseId": 1,
  "title": "경주 인생샷 투어 (수정)",
  "introduction": "황리단길과 첨성대 야경 코스를 일부분 수정했습니다.",
  "location": "경주 포석로",
  "thumbnailImageUrl": "https://s3.amazonaws.com/yourbucket/thumbnail.png?X-Amz-...",
  "startDate": "2025-03-01",
  "endDate": "2025-03-02",
  "forkCount": 12,
  "keywords": ["뚜벅이", "맛집탐방", "힐링"],
  "daySchedules": [
    {
      "dayScheduleId": 100,
      "day": 1,
      "places": [
        {
          "placeId": 200,
          "placeName": "황리단길 카페거리",
          "startTime": "10:30:00",
          "memo": "카페 골목 산책 및 브런치",
          "latitude": 35.8375,
          "longitude": 129.2123,
          "placeUrl": "http://place.map.kakao.com/26338954",
          "placeLocation": "경북 경주시 포석로 인근",
          "placeImages": [
            {
              "placeImageId": 500,
              "imageUrl": "https://s3.amazonaws.com/yourbucket/place1.png?X-Amz-..."
            },
            {
              "placeImageId": 501,
              "imageUrl": "https://s3.amazonaws.com/yourbucket/place2.png?X-Amz-..."
            }
          ]
        }
      ]
    }
  ]
}
```

---

## 8. 에러 응답

| 상황 | HTTP 상태 | `code` | `message` |
|---|---|---|---|
| 본인 작성 코스가 아닌 경우 수정 시도 | **403 FORBIDDEN** | `NOT_OWNED_UPLOAD_COURSE` | 본인이 업로드한 코스만 수정할 수 있습니다. |
| 존재하지 않는 `uploadCourseId` | **404 NOT FOUND** | `UPLOAD_COURSE_NOT_FOUND` | 업로드 코스를 찾을 수 없습니다. |
| 필수 필드(`title`, `startDate` 등) 누락 | **400 BAD REQUEST** | `MethodArgumentNotValidException` | 유효성 검사 실패 메시지 |

### 에러 응답 예시 (403 FORBIDDEN)
```json
{
  "timestamp": "2026-08-01T15:00:00.000000",
  "code": "NOT_OWNED_UPLOAD_COURSE",
  "message": "본인이 업로드한 코스만 수정할 수 있습니다."
}
```

---

## 9. 프론트엔드(Retrofit/OkHttp) 연동 팁

Retrofit 인터페이스 구성 예시:

```kotlin
interface UploadCourseApi {
    @Multipart
    @PUT("/api/upload-courses/{uploadCourseId}")
    suspend fun updateUploadCourse(
        @Path("uploadCourseId") uploadCourseId: Long,
        @Part("request") request: RequestBody, // MediaType: application/json
        @Part thumbnailImage: MultipartBody.Part? = null,
        @Part placeImages: List<MultipartBody.Part>? = null
    ): Response<UploadCourseDetailResponse>
}
```

- `request` 파트는 `RequestBody.create("application/json".toMediaTypeOrNull(), jsonString)` 형태로 전달해주시면 됩니다.
- 썸네일을 새로 바꾸지 않을 때는 `thumbnailImage` 파트를 아예 보내지 않거나 `null`로 보냅니다.
