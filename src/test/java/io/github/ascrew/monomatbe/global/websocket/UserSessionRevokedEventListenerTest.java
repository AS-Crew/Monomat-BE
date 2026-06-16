package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.websocket.event.SessionRevokedMessage;
import io.github.ascrew.monomatbe.global.websocket.event.UserSessionRevokedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserSessionRevokedEventListenerTest {

    private static final String SESSION_1 = "11111111-1111-1111-1111-111111111111";
    private static final String SESSION_2 = "22222222-2222-2222-2222-222222222222";

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private UserSessionRevokedEventListener listener;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        listener = new UserSessionRevokedEventListener(messagingTemplate);
    }

    @Test
    @DisplayName("revoke된 각 sessionId에게 /queue/auth로 RELOGIN 종료 알림을 전송한다")
    void sendsRevokeNotificationPerSession() {
        // when
        listener.handle(new UserSessionRevokedEvent(List.of(SESSION_1, SESSION_2)));

        // then
        ArgumentCaptor<SessionRevokedMessage> payloadCaptor = ArgumentCaptor.forClass(SessionRevokedMessage.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq(SESSION_1), eq(StompDestinations.SERVER_USER_SESSION_REVOKED), payloadCaptor.capture());
        verify(messagingTemplate).convertAndSendToUser(
                eq(SESSION_2), eq(StompDestinations.SERVER_USER_SESSION_REVOKED), payloadCaptor.capture());

        SessionRevokedMessage payload = payloadCaptor.getValue();
        assertThat(payload.code()).isEqualTo("SESSION_REVOKED");
        assertThat(payload.action()).isEqualTo("RELOGIN");
        assertThat(payload.message()).isNotBlank();
    }

    @Test
    @DisplayName("sessionId가 비어 있으면 아무것도 전송하지 않는다")
    void noopWhenEmpty() {
        // when
        listener.handle(new UserSessionRevokedEvent(List.of()));

        // then
        verify(messagingTemplate, never()).convertAndSendToUser(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("한 사용자 알림 전송이 실패해도 나머지 사용자에게는 계속 전송한다")
    void continuesWhenOneSendFails() {
        // given
        doThrow(new RuntimeException("broker down"))
                .when(messagingTemplate)
                .convertAndSendToUser(eq(SESSION_1), eq(StompDestinations.SERVER_USER_SESSION_REVOKED),
                        org.mockito.ArgumentMatchers.any());

        // when
        listener.handle(new UserSessionRevokedEvent(List.of(SESSION_1, SESSION_2)));

        // then - SESSION_2에는 정상 전송 시도
        verify(messagingTemplate, times(1)).convertAndSendToUser(
                eq(SESSION_2), eq(StompDestinations.SERVER_USER_SESSION_REVOKED),
                org.mockito.ArgumentMatchers.any());
    }
}
