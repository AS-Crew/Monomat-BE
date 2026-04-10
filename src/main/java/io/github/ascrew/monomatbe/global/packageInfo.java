package io.github.ascrew.monomatbe.global;

/*
  애플리케이션 전역에서 발생하는 예외를 공통으로 처리하는 컨트롤러 어드바이스 클래스입니다.

  비즈니스 로직과 무관한 인프라 설정 및 에러 처리를 담당하는 global 패키지의 핵심 요소입니다.
  영상 재생 불가 예외(VideoPlaybackException)와 같은 커스텀 에러나 예상치 못한 서버 에러를 포착하여
  클라이언트에게 일관된 에러 응답 형식으로 반환합니다.
 */