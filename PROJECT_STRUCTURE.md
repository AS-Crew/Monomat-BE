# 프로젝트 패키지 구조 및 설명

## 전체 구조

```
src/main/java/org/example/monomatbe/
├── MonomatBeApplication.java           # Spring Boot 시작점
│
├── controller/                         # HTTP 요청 처리 계층
│   ├── package-info.java
│   ├── HealthCheckController.java      # 예제 Controller
│   └── [DomainNameController].java     # 기타 Controller
│
├── service/                            # 비즈니스 로직 계층
│   ├── package-info.java
│   ├── ExampleService.java             # 예제 Service
│   └── [DomainName]Service.java        # 기타 Service
│
├── repository/                         # 데이터 접근 계층
│   ├── package-info.java
│   ├── ExampleRepository.java          # 예제 Repository
│   └── [DomainName]Repository.java     # 기타 Repository
│
├── domain/                             # 도메인 모델 계층
│   ├── package-info.java
│   ├── ExampleEntity.java              # 예제 Entity
│   └── [EntityName].java               # 기타 Entity
│
└── global/                             # 전역 공통 기능
    ├── package-info.java
    │
    ├── exception/                      # 예외 처리
    │   ├── package-info.java
    │   ├── BusinessException.java      # 비즈니스 예외
    │   └── GlobalExceptionHandler.java # 전역 예외 처리
    │
    ├── config/                         # Spring 설정
    │   ├── package-info.java
    │   ├── SecurityConfig.java         # Spring Security 설정
    │   ├── WebConfig.java              # Web 설정
    │   ├── JpaConfig.java              # JPA 설정
    │   ├── RedisConfig.java            # Redis 설정
    │   └── SwaggerConfig.java          # Swagger 설정
    │
    ├── constant/                       # 상수 정의
    │   ├── package-info.java
    │   ├── ApiConstant.java
    │   ├── ErrorConstant.java
    │   └── [NameConstant].java
    │
    ├── dto/                            # DTO 정의
    │   ├── package-info.java
    │   ├── ApiResponse.java            # 표준 API 응답
    │   ├── ErrorResponse.java          # 에러 응답
    │   ├── PageResponse.java           # 페이지네이션
    │   └── [EntityName]Dto.java
    │
    ├── util/                           # 유틸리티 함수
    │   ├── package-info.java
    │   ├── DateUtil.java
    │   ├── StringUtil.java
    │   ├── CryptoUtil.java
    │   └── [NameUtil].java
    │
    ├── filter/                         # Servlet Filter
    │   ├── package-info.java
    │   ├── LoggingFilter.java
    │   └── [CustomFilter].java
    │
    └── interceptor/                    # Spring Interceptor
        ├── package-info.java
        ├── LoggingInterceptor.java
        └── [CustomInterceptor].java
```

---

## 계층별 설명

### 1. **Controller (표현 계층)**
- 클라이언트의 HTTP 요청을 받아 처리
- Service 호출하여 비즈니스 로직 실행
- 응답 직렬화 및 클라이언트에 반환

**파일 예시:**
```java
@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService userService;
    
    @GetMapping("/{id}")
    public ApiResponse<UserDto> getUser(@PathVariable Long id) {
        return ApiResponse.success(userService.getUserById(id));
    }
}
```

---

### 2. **Service (비즈니스 로직 계층)**
- Controller에서 요청받은 작업 처리
- Repository를 통해 데이터 조회/수정
- 비즈니스 규칙 적용 및 검증
- 트랜잭션 관리

**파일 예시:**
```java
@Service
@Transactional
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    
    @Transactional(readOnly = true)
    public UserDto getUserById(Long id) {
        return userRepository.findById(id)
            .map(UserDto::from)
            .orElseThrow(() -> new BusinessException(404, "User not found"));
    }
}
```

---

### 3. **Repository (데이터 접근 계층)**
- Spring Data JPA를 사용한 데이터베이스 접근
- Entity 조회, 저장, 수정, 삭제 수행
- 커스텀 쿼리 정의 가능

**파일 예시:**
```java
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    List<User> findByNameContaining(String name);
}
```

---

### 4. **Domain (도메인 모델 계층)**
- JPA Entity 클래스 정의
- 데이터베이스 테이블과 매핑
- 비즈니스 로직을 담을 수 있음

**파일 예시:**
```java
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true)
    private String email;
    
    @Column(nullable = false)
    private String name;
}
```

---

### 5. **Global (전역 공통 기능)**

#### 5.1 Exception (예외 처리)
- 사용자 정의 예외 클래스 정의
- 전역 예외 핸들러로 일관된 에러 응답

**BusinessException 사용:**
```java
throw new BusinessException(404, "User not found");
```

#### 5.2 Config (설정)
- Spring 관련 설정 클래스
- 보안, 캐시, JPA, API 문서화 등 설정

#### 5.3 Constant (상수)
- 프로젝트 전체에서 사용되는 상수 정의
- 에러 코드, API 경로, 정규표현식 등

#### 5.4 DTO (Data Transfer Object)
- 계층 간 데이터 전달용 객체
- ApiResponse: 표준 API 응답 형식
- Entity와 분리하여 정보 은닉

**ApiResponse 사용:**
```java
ApiResponse.success("Success", data)              // 성공 응답
ApiResponse.fail(400, "Invalid request")          // 실패 응답
```

#### 5.5 Util (유틸리티)
- 재사용 가능한 공통 함수
- 날짜, 문자열, 암호화, 검증 등

#### 5.6 Filter (Servlet Filter)
- 요청/응답 필터링
- CORS, 인코딩, 로깅 등

#### 5.7 Interceptor (Spring Interceptor)
- 요청/응답 전처리/후처리
- 인증, 성능 모니터링, 로깅 등

---

## 개발 가이드

### 새로운 기능 개발 순서

1. **Entity 작성** (domain 패키지)
   ```bash
   # 예: User.java
   ```

2. **Repository 작성** (repository 패키지)
   ```bash
   # 예: UserRepository.java
   ```

3. **Service 작성** (service 패키지)
   ```bash
   # 예: UserService.java
   ```

4. **DTO 작성** (global/dto 패키지)
   ```bash
   # 예: UserRequestDto.java, UserResponseDto.java
   ```

5. **Controller 작성** (controller 패키지)
   ```bash
   # 예: UserController.java
   ```

6. **테스트 작성** (src/test)
   ```bash
   # 예: UserControllerTest.java, UserServiceTest.java
   ```

---

## 주의사항

- ✅ Service 계층에서 비즈니스 로직 처리
- ✅ @Transactional 어노테이션으로 트랜잭션 관리
- ✅ 예외는 BusinessException 또는 하위 클래스 사용
- ✅ Controller에서 API 응답은 ApiResponse 사용
- ❌ Controller에서 직접 Repository 접근 금지
- ❌ 민감한 정보(비밀번호, 토큰)는 응답 DTO에 포함하지 않기
- ❌ Service에서 HttpServletRequest/Response 사용 금지

---

## 관련 설정 파일

- `application.properties`: 기본 설정 (Virtual Threads 활성화)
- `application-dev.properties`: 개발 환경 설정
- `docker-compose.yml`: MySQL, Redis 컨테이너 설정
- `.gitignore`: Git 추적 제외 파일 설정
- `build.gradle`: 프로젝트 의존성 관리

