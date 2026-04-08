package org.example.monomatbe.controller;

import org.example.monomatbe.global.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API 엔드포인트 예시
 *
 * 실제 프로젝트에서는 이 파일을 삭제하고
 * 각 도메인별 Controller를 작성하세요.
 *
 * 예시:
 *
 * @RestController
 * @RequestMapping("/api/users")
 * @RequiredArgsConstructor
 * public class UserController {
 *     private final UserService userService;
 *
 *     @GetMapping("/{id}")
 *     public ResponseEntity<ApiResponse<UserDto>> getUser(@PathVariable Long id) {
 *         return ResponseEntity.ok(ApiResponse.success(userService.getUserById(id)));
 *     }
 * }
 */

@RestController
@RequestMapping("/api/health")
public class HealthCheckController {

    /**
     * 헬스 체크 엔드포인트
     */
    @GetMapping
    public ApiResponse<String> healthCheck() {
        return ApiResponse.success("Monomat-BE is running");
    }
}

