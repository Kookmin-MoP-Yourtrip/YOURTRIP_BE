package backend.yourtrip.domain.notification.controller;

import backend.yourtrip.domain.notification.dto.response.NotificationResponse;
import backend.yourtrip.domain.notification.dto.response.NotificationSettingResponse;
import backend.yourtrip.domain.notification.service.NotificationService;
import backend.yourtrip.domain.notification.service.NotificationSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationSettingService settingService;

    // =========================================================
    // 1) 알림 설정 조회
    // =========================================================
    @GetMapping("/settings")
    @Operation(
        summary = "알림 설정 조회",
        description = """
        ### 기능 설명
        - 로그인한 사용자의 **알림 수신 설정 상태**를 조회합니다.
        - 현재 지원하는 항목
          - `commentNoti` : 내 피드에 댓글이 달렸을 때 알림
          - `courseRemindNoti` : 내 코스 일정 리마인드(전날) 알림
          - `courseInviteNoti` : 코스 초대(협업) 알림

        ### 제약조건
        - **로그인 필수**: Authorization 헤더에 `Bearer {AccessToken}` 필요

        ### 예외상황 / 에러코드
        - `NOTI_SETTING_NOT_FOUND (404)`
          - 사용자 알림 설정 정보가 존재하지 않거나, 사용자 자체가 유효하지 않은 경우
          - 단, 기본 구현 상 사용자가 존재하면 설정을 자동 생성하도록 처리

        ### 정상 응답 예시
        ```json
        {
          "commentNoti": true,
          "courseRemindNoti": false,
          "courseInviteNoti": true
        }
        ```

        ### 에러 응답 예시
        ```json
        {
          "timestamp": "2025-11-22T17:00:00",
          "code": "NOTI_SETTING_NOT_FOUND",
          "message": "알림 설정 정보를 찾을 수 없습니다."
        }
        ```

        ### 테스트 방법
        1. Swagger Authorize 버튼 클릭 → `Bearer {AccessToken}` 입력
        2. `GET /api/notifications/settings` 호출
        3. comment / remind / invite 항목의 true/false 값을 확인
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "알림 설정 조회 성공",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = NotificationSettingResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "알림 설정 정보를 찾을 수 없음",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject("""
                {
                  "timestamp": "2025-11-22T17:00:00",
                  "code": "NOTI_SETTING_NOT_FOUND",
                  "message": "알림 설정 정보를 찾을 수 없습니다."
                }
                """)
            )
        )
    })
    public NotificationSettingResponse getSettings() {
        return settingService.getSettings();
    }

    // =========================================================
    // 2) 알림 설정 토글 - 댓글 알림
    // =========================================================
    @PatchMapping("/settings/comment")
    @Operation(
        summary = "피드 댓글 알림 설정 토글",
        description = """
        ### 기능 설명
        - **내 피드에 댓글이 달렸을 때** 받는 알림의 수신 여부를 토글합니다.
        - 현재 상태를 반전시킨 뒤, 변경된 전체 설정 값을 반환합니다.

        ### 제약조건
        - 로그인 필수 (Bearer Token)
        - 토글은 **현재 사용자 기준**으로만 동작

        ### 정상 동작 예시
        - 기존 `commentNoti = true` → 호출 후 `false` 로 변경
        - 기존 `commentNoti = false` → 호출 후 `true` 로 변경

        ### 예외상황 / 에러코드
        - `NOTI_SETTING_NOT_FOUND (404)`
          - 사용자 또는 해당 사용자의 설정 정보를 찾을 수 없는 경우

        ### 정상 응답 예시
        ```json
        {
          "commentNoti": false,
          "courseRemindNoti": true,
          "courseInviteNoti": true
        }
        ```

        ### 테스트 방법
        1. `GET /api/notifications/settings`로 현재 상태 확인
        2. `PATCH /api/notifications/settings/comment` 호출
        3. 다시 설정 조회하여 `commentNoti` 값이 반전되었는지 확인
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "댓글 알림 설정 토글 성공",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = NotificationSettingResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "알림 설정 정보 없음",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject("""
                {
                  "timestamp": "2025-11-22T17:05:00",
                  "code": "NOTI_SETTING_NOT_FOUND",
                  "message": "알림 설정 정보를 찾을 수 없습니다."
                }
                """)
            )
        )
    })
    public NotificationSettingResponse toggleComment() {
        return settingService.toggleComment();
    }

    // =========================================================
    // 3) 알림 설정 토글 - 코스 일정 리마인드
    // =========================================================
    @PatchMapping("/settings/remind")
    @Operation(
        summary = "코스 일정 리마인드 알림 설정 토글",
        description = """
        ### 기능 설명
        - **내 코스 일정(전날 리마인드)** 알림 수신 여부를 토글합니다.
        - 일정 리마인드 스케줄러가 동작할 때, 이 값이 `true`인 사용자에게만 알림을 발송합니다.

        ### 제약조건
        - 로그인 필수

        ### 정상 응답 예시
        ```json
        {
          "commentNoti": true,
          "courseRemindNoti": false,
          "courseInviteNoti": true
        }
        ```

        ### 에러 응답 예시
        ```json
        {
          "timestamp": "2025-11-22T17:06:00",
          "code": "NOTI_SETTING_NOT_FOUND",
          "message": "알림 설정 정보를 찾을 수 없습니다."
        }
        ```

        ### 테스트 방법
        1. `GET /api/notifications/settings`로 상태 확인
        2. `PATCH /api/notifications/settings/remind` 호출
        3. `courseRemindNoti` 값이 반전되었는지 확인
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "코스 일정 리마인드 알림 토글 성공",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = NotificationSettingResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "알림 설정 정보 없음",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject("""
                {
                  "timestamp": "2025-11-22T17:06:00",
                  "code": "NOTI_SETTING_NOT_FOUND",
                  "message": "알림 설정 정보를 찾을 수 없습니다."
                }
                """)
            )
        )
    })
    public NotificationSettingResponse toggleRemind() {
        return settingService.toggleCourseRemind();
    }

    // =========================================================
    // 4) 알림 설정 토글 - 코스 초대 알림
    // =========================================================
    @PatchMapping("/settings/invite")
    @Operation(
        summary = "코스 초대 알림 설정 토글",
        description = """
        ### 기능 설명
        - **코스 협업 초대**가 왔을 때 받을 알림의 수신 여부를 토글합니다.

        ### 제약조건
        - 로그인 필수

        ### 정상 응답 예시
        ```json
        {
          "commentNoti": true,
          "courseRemindNoti": true,
          "courseInviteNoti": false
        }
        ```

        ### 에러 응답 예시
        ```json
        {
          "timestamp": "2025-11-22T17:07:00",
          "code": "NOTI_SETTING_NOT_FOUND",
          "message": "알림 설정 정보를 찾을 수 없습니다."
        }
        ```

        ### 테스트 방법
        1. `PATCH /api/notifications/settings/invite` 호출
        2. 응답에서 `courseInviteNoti` 값 확인
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "코스 초대 알림 토글 성공",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = NotificationSettingResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "알림 설정 정보 없음",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject("""
                {
                  "timestamp": "2025-11-22T17:07:00",
                  "code": "NOTI_SETTING_NOT_FOUND",
                  "message": "알림 설정 정보를 찾을 수 없습니다."
                }
                """)
            )
        )
    })
    public NotificationSettingResponse toggleInvite() {
        return settingService.toggleCourseInvite();
    }

    // =========================================================
    // 5) 알림 리스트 조회
    // =========================================================
    @GetMapping
    @Operation(
        summary = "알림 리스트 조회",
        description = """
        ### 기능 설명
        - 로그인한 사용자의 **알림 내역 전체를 최신순으로 조회**합니다.
        - 각 알림에는 다음 정보가 포함됩니다.
          - `notificationId` : 알림 ID
          - `type` : COMMENT / COURSE_REMIND / COURSE_INVITE
          - `message` : 화면에 보여줄 알림 문구
          - `readFlag` : 읽음 여부
          - `createdAt` : 생성 일시 (yyyy-MM-dd HH:mm 형식)

        ### 제약조건
        - 로그인 필수
        - soft delete 된 알림(`deleted = true`)은 조회되지 않음

        ### 정상 응답 예시
        ```json
        [
          {
            "notificationId": 10,
            "type": "COMMENT",
            "message": "다른 사용자가 내 피드에 댓글을 남겼습니다.",
            "readFlag": false,
            "createdAt": "2025-11-22 17:10"
          },
          {
            "notificationId": 9,
            "type": "COURSE_REMIND",
            "message": "내일 '제주 2박 3일 일정' 여행이 시작됩니다.",
            "readFlag": true,
            "createdAt": "2025-11-21 09:00"
          }
        ]
        ```

        ### 테스트 방법
        1. 테스트용으로 댓글/코스 초대/리마인드 등에서 알림을 생성
        2. `GET /api/notifications` 호출
        3. type, message, readFlag, createdAt 값 확인
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "알림 리스트 조회 성공",
            content = @Content(
                mediaType = "application/json",
                array = @ArraySchema(schema = @Schema(implementation = NotificationResponse.class))
            )
        )
    })
    public List<NotificationResponse> getNotiList() {
        return notificationService.getNotifications();
    }

    // =========================================================
    // 6) 알림 읽음 처리
    // =========================================================
    @PatchMapping("/{notificationId}/read")
    @Operation(
        summary = "알림 읽음 처리",
        description = """
        ### 기능 설명
        - 특정 알림(`notificationId`)의 `readFlag`를 **true(읽음)** 상태로 변경합니다.
        - 프론트에서는 이 API 호출 후, 리스트를 다시 조회하거나 로컬 상태를 업데이트하면 됩니다.

        ### 제약조건
        - 로그인 필수
        - 존재하지 않는 알림 ID에 대해 호출 시 예외 발생

        ### 예외상황 / 에러코드
        - `NOTIFICATION_NOT_FOUND (404)`
          - 전달된 ID에 해당하는 알림이 없는 경우

        ### 정상 응답
        - 200 OK (본문 없음)

        ### 에러 응답 예시
        ```json
        {
          "timestamp": "2025-11-22T17:15:00",
          "code": "NOTIFICATION_NOT_FOUND",
          "message": "알림을 찾을 수 없습니다."
        }
        ```

        ### 테스트 방법
        1. `GET /api/notifications`로 notificationId 확인
        2. `PATCH /api/notifications/{notificationId}/read` 호출
        3. 다시 리스트 조회하여 해당 알림의 `readFlag`가 true인지 확인
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "알림 읽음 처리 성공"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "알림 없음",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject("""
                {
                  "timestamp": "2025-11-22T17:15:00",
                  "code": "NOTIFICATION_NOT_FOUND",
                  "message": "알림을 찾을 수 없습니다."
                }
                """)
            )
        )
    })
    public void markRead(@PathVariable Long notificationId) {
        notificationService.markRead(notificationId);
    }

    // =========================================================
    // 7) 알림 개별 삭제 (Soft Delete)
    // =========================================================
    @DeleteMapping("/{notificationId}")
    @Operation(
        summary = "알림 개별 삭제",
        description = """
        ### 기능 설명
        - 특정 알림을 **소프트 삭제(soft delete)** 합니다.
        - DB에서는 deleted = true 로만 표시되고, 실제로는 남아 있지만
          - `GET /api/notifications` 응답에서는 더 이상 조회되지 않습니다.

        ### 제약조건
        - 로그인 필수
        - 자신의 알림이 아닌 경우에 대한 검증은 서비스 레벨에서 추가 구현 가능

        ### 예외상황 / 에러코드
        - `NOTIFICATION_NOT_FOUND (404)`
          - 이미 삭제되었거나 존재하지 않는 알림 ID인 경우

        ### 정상 응답
        - 200 OK (본문 없음)

        ### 에러 응답 예시
        ```json
        {
          "timestamp": "2025-11-22T17:18:00",
          "code": "NOTIFICATION_NOT_FOUND",
          "message": "알림을 찾을 수 없습니다."
        }
        ```

        ### 테스트 방법
        1. `GET /api/notifications`로 ID 확인
        2. `DELETE /api/notifications/{notificationId}` 호출
        3. 다시 리스트 조회하여 해당 알림이 사라졌는지 확인
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "알림 개별 삭제(soft delete) 성공"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "알림 없음",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject("""
                {
                  "timestamp": "2025-11-22T17:18:00",
                  "code": "NOTIFICATION_NOT_FOUND",
                  "message": "알림을 찾을 수 없습니다."
                }
                """)
            )
        )
    })
    public void deleteOne(@PathVariable Long notificationId) {
        notificationService.deleteNotification(notificationId);
    }

    // =========================================================
    // 8) 알림 전체 삭제
    // =========================================================
    @DeleteMapping
    @Operation(
        summary = "전체 알림 삭제",
        description = """
        ### 기능 설명
        - 현재 로그인한 사용자의 **모든 알림을 soft delete** 처리합니다.
        - DB에는 남아 있지만, `deleted = true` 상태로 표시되어 더 이상 조회되지 않습니다.

        ### 제약조건
        - 로그인 필수

        ### 정상 응답
        - 200 OK (본문 없음)

        ### 테스트 방법
        1. 알림이 여러 개 존재하는 상태에서
        2. `DELETE /api/notifications` 호출
        3. `GET /api/notifications` 재호출 → 빈 배열 `[]` 응답 확인
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "전체 알림 삭제(soft delete) 성공"
        )
    })
    public void deleteAll() {
        notificationService.deleteAllNotifications();
    }
}