package io.github.ascrew.monomatbe.domain.report.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 로비 채팅 메시지 신고 스냅샷 엔티티
 *
 * [설계 의도]
 * 로비 최근 채팅은 Redis List에 TTL 기반으로 저장된다.
 * 따라서 신고 접수 후 Redis TTL이 만료되면 운영자가 신고 대상 메시지 원문을 확인할 수 없다.
 *
 * 이를 방지하기 위해 채팅 메시지 신고가 접수되는 시점의 메시지 정보를
 * report와 1:1로 연결된 별도 스냅샷 테이블에 저장한다.
 *
 * [저장 대상]
 * - messageId: Redis 최근 채팅 메시지 식별자
 * - senderIdentifier: Redis/WebSocket userIdentifier
 * - senderId: users.id, 조회 실패 또는 과거 메시지 호환성 때문에 nullable
 * - senderNickname: 신고 시점의 표시 닉네임
 * - content: 신고 시점의 메시지 본문
 * - messageType: CHAT
 * - sentAt: 메시지 발신 시각
 *
 * [Report와 분리하는 이유]
 * Report는 로비/유저/채팅 등 모든 신고의 공통 정보만 가진다.
 * 채팅 메시지 전용 필드를 Report에 직접 추가하면 다른 신고 타입에서 null 컬럼이 늘어나므로
 * 채팅 신고 스냅샷 책임을 별도 엔티티로 분리한다.
 *
 * [스키마 관리 기준]
 * 운영 DB 스키마와 인덱스는 Flyway migration SQL을 기준으로 관리한다.
 * 따라서 이 엔티티에는 테이블명과 컬럼 매핑만 둔다.
 *
 * [시간 정책]
 * sentAt이 UTC 기준으로 파싱되어 저장되므로,
 * 스냅샷 저장 시각인 createdAt도 UTC 기준으로 저장한다.
 */
@Getter
@Entity
@Builder
@Table(name = "lobby_chat_message_report_snapshot")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LobbyChatMessageReportSnapshot {

    private static final int MESSAGE_ID_MAX_LENGTH = 64;
    private static final int SENDER_IDENTIFIER_MAX_LENGTH = 100;
    private static final int SENDER_NICKNAME_MAX_LENGTH = 50;
    private static final int CONTENT_MAX_LENGTH = 500;
    private static final int MESSAGE_TYPE_MAX_LENGTH = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 연결된 신고
     *
     * 하나의 채팅 메시지 신고는 하나의 스냅샷만 가진다.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "report_id",
            nullable = false,
            unique = true,
            foreignKey = @ForeignKey(name = "fk_lobby_chat_message_snapshot_report")
    )
    private Report report;

    /**
     * Redis 최근 채팅 메시지 식별자
     */
    @Column(name = "message_id", nullable = false, length = MESSAGE_ID_MAX_LENGTH)
    private String messageId;

    /**
     * Redis/WebSocket에서 사용하는 사용자 식별자
     *
     * 게스트는 guestToken, 회원은 sessionId 성격의 식별자를 사용한다.
     */
    @Column(name = "sender_identifier", nullable = false, length = SENDER_IDENTIFIER_MAX_LENGTH)
    private String senderIdentifier;

    /**
     * 발신자 users.id
     *
     * 신규 메시지는 senderId를 포함하지만,
     * 조회 실패나 과거 Redis payload 호환성을 고려해 nullable로 둔다.
     */
    @Column(name = "sender_id")
    private Long senderId;

    /**
     * 신고 시점의 발신자 닉네임
     */
    @Column(name = "sender_nickname", length = SENDER_NICKNAME_MAX_LENGTH)
    private String senderNickname;

    /**
     * 신고 시점의 채팅 메시지 본문
     */
    @Column(name = "content", nullable = false, length = CONTENT_MAX_LENGTH)
    private String content;

    /**
     * 신고 시점의 메시지 타입
     *
     * ChatMessageDto.MessageType.name() 값을 저장한다.
     */
    @Column(name = "message_type", nullable = false, length = MESSAGE_TYPE_MAX_LENGTH)
    private String messageType;

    /**
     * 메시지 발신 시각
     */
    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    /**
     * 스냅샷 저장 시각
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}