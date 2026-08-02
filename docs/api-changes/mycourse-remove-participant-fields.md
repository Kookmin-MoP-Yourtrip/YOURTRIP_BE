# API 변경사항 — 나의 코스 API에서 memberCount / role 필드 제거

> 이 문서는 프론트엔드(Android) 담당자/에이전트에게 전달하기 위한 API 변경사항 요약입니다. **엔드포인트 추가/삭제는 없고, 기존 응답 3개에서 필드 2개가 삭제**되었습니다.

## 요약

| 항목 | 내용 |
|---|---|
| 변경 종류 | 기존 응답 필드 삭제(breaking change) — 엔드포인트/요청 스펙 변경 없음 |
| 영향받는 엔드포인트 | `POST /api/my-courses`, `GET /api/my-courses`, `GET /api/my-courses/{courseId}` |
| 삭제된 필드 | `memberCount`(3개 응답 전부), `role`(`GET /api/my-courses/{courseId}` 응답만) |
| 배경 | 코스를 여러 명이 함께 편집하는 기능(참여자 초대 등)이 애초에 구현된 적이 없어, 백엔드 내부 데이터 모델을 단순화하면서 이 필드들을 정리했습니다 |

## 왜 지워도 안전한가

- `role`은 지금까지 **항상 `"OWNER"`만** 내려갔습니다. 코스에 다른 사람을 초대하는 API 자체가 존재한 적이 없어서, 이 값이 `"PARTICIPANT"`로 내려온 적이 한 번도 없습니다.
- `memberCount`도 마찬가지로 **항상 `1`만** 내려갔습니다.
- 즉 이 필드들을 화면에서 참고하고 있었더라도, 실질적으로 잃는 정보는 없습니다. 관련 UI(예: "편집 인원 N명" 표시, 역할 배지 등)가 있다면 제거하거나 고정 텍스트로 대체하면 됩니다.

## 엔드포인트별 변경 상세

### `POST /api/my-courses` (나의 코스 생성)

**Before**
```json
{
  "myCourseId": 1,
  "title": "개쩌는 경주 여행기",
  "location": "경주",
  "startDate": "2025-10-31",
  "endDate": "2025-11-02",
  "memberCount": 1
}
```

**After**
```json
{
  "myCourseId": 1,
  "title": "개쩌는 경주 여행기",
  "location": "경주",
  "startDate": "2025-10-31",
  "endDate": "2025-11-02"
}
```

### `GET /api/my-courses` (나의 코스 목록 조회)

**Before** — 리스트의 각 아이템에 `memberCount` 포함
```json
{
  "myCourses": [
    {
      "courseId": 1,
      "title": "개쩌는 호주 여행기",
      "location": "호주",
      "startDate": "2025-10-31",
      "endDate": "2025-11-02",
      "memberCount": 1
    }
  ]
}
```

**After**
```json
{
  "myCourses": [
    {
      "courseId": 1,
      "title": "개쩌는 호주 여행기",
      "location": "호주",
      "startDate": "2025-10-31",
      "endDate": "2025-11-02"
    }
  ]
}
```

### `GET /api/my-courses/{courseId}` (나의 코스 단건 조회)

**Before** — `memberCount`, `role` 포함
```json
{
  "courseId": 1,
  "title": "개쩌는 경주 여행기",
  "location": "경주",
  "startDate": "2025-10-31",
  "endDate": "2025-11-02",
  "memberCount": 1,
  "role": "OWNER",
  "updatedAt": "2025-11-10T11:00:00",
  "daySchedules": [
    { "dayId": 1, "day": 1 }
  ]
}
```

**After**
```json
{
  "courseId": 1,
  "title": "개쩌는 경주 여행기",
  "location": "경주",
  "startDate": "2025-10-31",
  "endDate": "2025-11-02",
  "updatedAt": "2025-11-10T11:00:00",
  "daySchedules": [
    { "dayId": 1, "day": 1 }
  ]
}
```

## 화면 영향 가이드

| UI 요소 | 대응 방법 |
|---|---|
| 코스 카드/상세에 "편집 인원 N명" 같은 표시가 있는 경우 | 제거 (실질적으로 항상 1명이었음) |
| 코스 상세에 `role` 기반으로 "초대" 버튼이나 "편집 권한" 배지를 조건부로 그리는 로직이 있는 경우 | 조건 분기를 제거하고, 코스 소유자는 항상 모든 편집 권한을 갖는 것으로 가정해도 됨 |

## 참고 — 이번 변경에 포함되지 않은 것

- 요청 바디/쿼리 파라미터는 전혀 바뀌지 않았습니다(`POST /api/my-courses`의 요청 스펙 동일).
- `PATCH`/`DELETE` 등 다른 나의 코스 API(장소 추가/수정/삭제, 시간·메모 수정, 사진 추가/삭제, 포크, AI 코스 생성)는 요청/응답 모두 변경 없습니다.
- 서버 내부적으로 "업로드 코스가 원본 나의 코스와 독립적인 데이터를 갖도록" 저장 구조를 바꾸는 작업(사본 생성 메커니즘)도 이번에 함께 진행했지만, 이는 순수 백엔드 내부 구현이라 어떤 API의 요청/응답 스키마에도 영향을 주지 않습니다. 업로드 코스 자체를 수정하는 기능(제목/일정/장소 편집 API)은 아직 없으며, 추가되면 별도 문서로 안내합니다.
