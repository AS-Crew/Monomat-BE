# 로컬 개발 환경 설정 가이드

## 개요
이 프로젝트는 Docker를 사용하여 MySQL 8.x와 Redis를 로컬 개발 환경에서 실행합니다.

## 사전 요구사항
- Docker & Docker Compose 설치
- Java 21 이상
- Gradle 9.4.1 이상

## 빠른 시작

### 1. 환경 변수 설정
```bash
# .env.example을 복사하여 .env 파일 생성
cp .env.example .env

# .env 파일을 텍스트 에디터로 열어 비밀번호 설정
# MYSQL_ROOT_PASSWORD=your_secure_password_here
# MYSQL_PASSWORD=your_monomat_password_here
```

**⚠️ 중요**: .env 파일에는 민감한 정보가 포함되므로 절대 Git에 커밋하지 마세요.
`.gitignore`에 `.env`가 포함되어 있습니다.

### 2. Spring Boot 설정 파일 생성
```bash
# application-dev.properties.sample을 복사하여 application-dev.properties 생성
cp src/main/resources/application-dev.properties.sample src/main/resources/application-dev.properties

# 필요시 application-dev.properties의 DB 비밀번호를 .env 값과 동일하게 설정
```

### 3. Docker 컨테이너 실행
```bash
# 프로젝트 루트 디렉토리에서
docker-compose up -d
```

### 4. 컨테이너 상태 확인
```bash
docker-compose ps
```

예상 출력:
```
NAME                COMMAND                  SERVICE             STATUS
monomat-mysql       --character-set-server   mysql               Up (healthy)
monomat-redis       redis-server --append    redis               Up (healthy)
```

### 5. 애플리케이션 실행
```bash
# dev 프로필로 실행
./gradlew bootRun --args='--spring.profiles.active=dev'

# 또는 IDE에서 실행 시 환경 변수 설정
# SPRING_PROFILES_ACTIVE=dev
```

## 서비스 상세 정보

### MySQL 8.0
- **호스트**: localhost
- **포트**: 3306
- **데이터베이스**: monomat_dev (환경 변수: `MYSQL_DATABASE`)
- **Root 비밀번호**: .env 파일의 `MYSQL_ROOT_PASSWORD` 참조
- **사용자**: monomat (환경 변수: `MYSQL_USER`)
- **사용자 비밀번호**: .env 파일의 `MYSQL_PASSWORD` 참조
- **저장소**: `mysql_data` 볼륨 (재시작 시에도 데이터 유지)

### Redis 7.2
- **호스트**: localhost
- **포트**: 6379
- **비밀번호**: 없음 (로컬 개발 환경)
- **최대 메모리**: 512MB
- **정책**: allkeys-lru (메모리 초과 시 자동 제거)
- **저장소**: `redis_data` 볼륨 (영속성 설정)

## 유용한 명령어

### 컨테이너 관리
```bash
# 모든 컨테이너 시작
docker-compose up -d

# 모든 컨테이너 중지
docker-compose down

# 볼륨 포함하여 완전 제거 (데이터 삭제됨)
docker-compose down -v

# 로그 확인
docker-compose logs -f mysql
docker-compose logs -f redis

# 특정 컨테이너 재시작
docker-compose restart mysql
docker-compose restart redis
```

### MySQL 접속
```bash
# 컨테이너 내 MySQL CLI 사용
docker-compose exec mysql mysql -u root -ppassword -D monomat_dev

# 또는 로컬 MySQL 클라이언트 사용 (설치 필요)
mysql -h localhost -u root -ppassword -D monomat_dev
```

### Redis 접속
```bash
# 컨테이너 내 Redis CLI 사용
docker-compose exec redis redis-cli

# Redis 명령어 예시
# PING
# KEYS *
# FLUSHALL
```

## 문제 해결

### 포트 충돌 오류
```
Error: Ports are not available
```
**해결**: 다른 MySQL/Redis가 실행 중인지 확인
```bash
lsof -i :3306
lsof -i :6379
```

### MySQL 컨테이너가 Unhealthy 상태
```bash
# 로그 확인
docker-compose logs mysql

# 컨테이너 재시작
docker-compose restart mysql
```

### Redis 연결 불가
```bash
# Redis 상태 확인
docker-compose exec redis redis-cli ping

# 결과: PONG (정상)
```

### 데이터 초기화 필요
```bash
# 모든 데이터 삭제 후 재시작
docker-compose down -v
docker-compose up -d
```

## 설정 파일 위치

| 파일 | 역할 | 주의사항 |
|------|------|--------|
| `.env.example` | 환경 변수 샘플 | 버전 관리에 포함 |
| `.env` | 환경 변수 (실제 값) | **Git에 커밋하지 마세요** |
| `docker-compose.yml` | Docker 컨테이너 구성 | 버전 관리에 포함 |
| `init.sql` | MySQL 초기화 스크립트 | 버전 관리에 포함 |
| `application-dev.properties.sample` | Spring Boot 개발 설정 샘플 | 버전 관리에 포함 |
| `application-dev.properties` | Spring Boot 개발 설정 (실제 값) | **Git에 커밋하지 마세요** |

## Virtual Threads 설정
Spring Boot 4.0.5 + Java 21에서 Virtual Threads가 자동으로 활성화됩니다.
- 설정: `spring.threads.virtual.enabled=true` (application-dev.properties)

## 보안 체크리스트

로컬 개발 환경을 구성할 때 다음을 확인하세요:

- [ ] `.env` 파일이 `.gitignore`에 포함되어 있는지 확인
- [ ] `application-dev.properties` 파일이 `.gitignore`에 포함되어 있는지 확인
- [ ] 비밀번호를 실제 강력한 값으로 설정 (`.env.example` 참고)
- [ ] Docker 이미지를 정기적으로 업데이트 (`docker-compose pull`)
- [ ] 민감한 정보(API 키, DB 비밀번호 등)는 소스 코드에 포함시키지 않음

### 프로덕션 배포 전 필수 사항

```bash
# 프로덕션용 환경 변수 설정
cp .env.example .env.prod

# application-dev.properties를 application-prod.properties로 생성
cp src/main/resources/application-dev.properties.sample src/main/resources/application-prod.properties

# application-prod.properties 내용 수정 (보안 강화 필요)
# - 데이터베이스 비밀번호 변경
# - 로깅 레벨 조정 (DEBUG → INFO/WARN)
# - 불필요한 설정 제거
```

## 참고사항
- 개발 환경용 설정이므로 프로덕션에서는 암호 변경 및 보안 강화 필요
- MySQL 및 Redis 데이터는 Docker 볼륨에 저장되어 컨테이너 재시작 시에도 유지됨
- 첫 시작 시 init.sql 스크립트가 자동으로 실행됨
- 비밀번호는 docker-compose.yml이 아닌 .env 파일에서 관리

