-- MySQL 초기화 스크립트
-- 데이터베이스 및 사용자 권한 설정

-- monomat_dev 데이터베이스 생성 (이미 environment에서 MYSQL_DATABASE로 생성됨)
CREATE DATABASE IF NOT EXISTS monomat_dev CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 데이터베이스 선택
USE monomat_dev;

-- ⚠️ 주의: 사용자 및 비밀번호는 docker-compose.yml의 환경 변수로 관리됩니다.
-- 이 스크립트에서는 권한 설정만 수행합니다.

-- root 사용자 권한 설정
GRANT ALL PRIVILEGES ON monomat_dev.* TO 'root'@'%';

-- monomat 사용자 권한 설정 (docker-compose.yml의 MYSQL_USER로 생성됨)
GRANT ALL PRIVILEGES ON monomat_dev.* TO 'monomat'@'%';

-- 권한 적용
FLUSH PRIVILEGES;

-- 초기 스키마 또는 테스트 데이터 추가 (선택사항)
-- 예: CREATE TABLE users (id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100));
