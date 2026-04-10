package io.github.ascrew.monomatbe.service;

/*
  게임 진행과 관련된 복잡한 비즈니스 로직을 수행하고 트랜잭션을 관리하는 서비스 클래스입니다.

  컨트롤러와 리포지토리 사이에서 다음과 같은 핵심적인 작업을 처리합니다:
  - 외부 oEmbed API 프록시 호출 및 응답 데이터 가공
  - 가상 스레드(Virtual Threads)를 활용한 동시 정답자 순위 판별 및 동시성 제어
  - 게임 동기화를 위한 클라이언트-서버 간 시차(Clock Drift) 보정 로직 연산
 */