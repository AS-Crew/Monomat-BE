package io.github.ascrew.monomatbe.controller;

/*
  클라이언트(React)의 실시간 게임 이벤트를 처리하고 응답을 반환하는 WebSocket 컨트롤러입니다.

  STOMP 프로토콜을 활용하여 클라이언트의 메시지(정답 제출, 채팅 등)를 수신합니다.
  입력 파라미터를 검증한 뒤 실제 처리는 GameService로 위임하며,
  처리된 결과를 구독 중인 클라이언트들에게 다시 브로드캐스팅합니다.
 */