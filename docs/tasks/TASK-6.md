# TASK-6. 캐시 무효화 연결

> [CACHING-ROADMAP.md](../CACHING-ROADMAP.md) 6번 섹션("캐시 무효화 연결")에 대응하는 작업 기록. 6번 섹션 자체는 아직 착수 전이지만, 다른 브랜치(dev) 병합 과정에서 착수 시점에 알아야 할 배경과 이미 존재하는 gap이 드러나 미리 기록해둔다.

## 배경 — dev 브랜치 병합으로 달라진 전제

캐싱 브랜치가 갈라진 이후 `origin/dev`에는 PR #52와 PR #54가 병합됐고, 캐싱 브랜치도 이를 병합해 반영했다(관련 커밋: `fe6c930` 머지 커밋).

- **PR #52 — 업로드 코스 독립 사본화**: 업로드 시점에 fork와 동일한 딥카피로 원본과 완전히 독립된 사본을 만들어, 업로드 코스가 그 사본을 참조하게 바뀌었다. `MyCourse` 엔티티는 `TravelCourse`로 리네임됐다. 이 변경으로 **원본 "내 코스"를 수정해도 이미 업로드된 코스에는 더 이상 영향을 주지 않는다** — 로드맵 원문 6-3("원본 일정/장소 수정 시 업로드된 코스라면 상세 캐시 evict")이 전제하던 상황 자체가 사라졌다. 6-3 문구를 "업로드 코스 직접 수정 시 캐시 evict"로 갱신했다.
- **PR #54 — 업로드 코스 직접 수정 API**: `PUT /api/upload-courses/{uploadCourseId}`가 새로 생겼다. 제목/소개/위치/기간/키워드/썸네일/일정/장소/장소사진을 전부 수정할 수 있다(자세한 스펙은 dev의 `docs/api-changes/upload-course-update.md`). 즉 상세 캐시(`courseDetail`)와 아이템 캐시(`courseListItem`)가 담는 필드 전부가 이제 "정적이지 않은" 필드가 됐다.

## 발견한 gap — 수정 API에 캐시 무효화가 없다

`UploadCourseServiceImpl.updateUploadCourse(...)`(dev에서 병합됨)는 `TravelCourse`/`UploadCourse`/`DaySchedule`/`Place`/`PlaceImage`를 갱신한 뒤 곧바로 최신 데이터로 응답을 만들어 반환하지만, **`courseDetail`/`courseListItem` 캐시를 evict하거나 다시 쓰지 않는다.** 그 결과 수정 직후에도 다른 요청(또는 evict 전에 캐시가 채워져 있던 동일 요청)은 최대 TTL(2시간) 동안 옛 데이터를 계속 받을 수 있다.

흥미롭게도 dev 커밋 히스토리에는 이 부분을 처리하려던 흔적이 있다 — `99ad4a9`("refactor: 업로드 코스 수정 시 캐시 무효화 로직 제거") 커밋에서 `courseListItem` evict + `popularCourses` 전체 clear 코드를 작성했다가 직접 제거했다. 캐싱 브랜치가 나중에 병합되며 이 부분을 제대로(우리 설계 원칙에 맞게) 처리하기를 기다린 것으로 보인다.

## 6번 섹션 착수 시 반영할 것

- **6-3 구현 방향**: `updateUploadCourse` 끝에서 `writeDetailCache`/`writeItemCache`를 재사용한 write-through를 권장한다(evict 후 재조회를 기다리기보다, 이미 갱신된 엔티티를 그대로 캐시에 즉시 반영). `fork`로 인한 `forkCount` 변경 시 `writeItemCache`를 쓰는 기존 패턴과 동일한 스타일이다.
- **`popularCourses`(랭킹 캐시) 전체 clear는 하지 않는다.** `99ad4a9`에서 제거된 `popularCache.clear()`는 우리가 TASK-3.md에서 이미 검토하고 기각한 방식과 같다("검토했던 안 1", 삭제와 무관한 테마 캐시까지 매번 무효화되는 문제) — 수정 API도 `view_count`를 바꾸지 않으므로 랭킹 캐시는 손댈 필요가 없고, TTL/스케줄러로 자연히 정합성이 맞춰지도록 둔다.
- **현재(6번 섹션 착수 전) 상태의 known gap**: 업로드 코스를 직접 수정하면 최대 2시간 동안 상세/목록 캐시가 옛 데이터를 반환할 수 있다. 실사용 트래픽이 아직 없는 개발 단계라 실질적 영향은 없지만, 6번 섹션을 후순위로 미루지 않는 이유이기도 하다.
