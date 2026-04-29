package io.github.ascrew.monomatbe.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

// GAME_LOBBY 테이블과 1:1로 매핑되는 JPA 엔티티
// invite_code :
// - 유저가 직접 입력하거나 딥링크 (/lobby/{code})로 공유되는 6자리 고유 코드
// is_private :
// - 공개/비공개 설정값
// - Redis의 lobby:public Set 관리 로직과 직접 연동

@Entity
@Table(
        name = "GAME_LOBBY",
        // DB 레벨 UNIQUE 제약: 애플리케이션 레벨 중복 체크
        uniqueConstraints = @UniqueConstraint(
                name = "uk_game_lobby_invite_code",
                columnNames = "invite_code"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 스펙상 기본 생성자 필요, 외부 직접 생성 방지
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE) // Builder를 통해서만 생성 가능하도록 제한
public class GameLobby {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // MySQL AUTO_INCREMENT 전략
    private Long id;

    // 로비를 생성한 방장의 user.id (FK)
    // nullable = false: 방장 없는 로비는 존재할 수 없다.
    @Column(name = "host_user_id", nullable = false)
    private Long hostUserId;

    // 선택된 맵 세트의 map.id (FK)
    // 로비 생성 시점에는 맵이 선택되지 않을 수 있으므로 nullable = true
    @Column(name = "map_id")
    private Long mapId;

    // 서버 내부 식별용 UUID
    // 유저에게 노출되는 invite_code와 다름
    @Column(name = "uuid", nullable = false, length = 225)
    private String uuid;

    // 유저가 직접 입력하거나 딥링크로 공유되는 6자리 초대 코드
    // - length = 6
    // - unique = true : @Table의 uniqueConstraints와 이중으로 선언하여 Hibernate의 스키마 생성과 JPA 명세 양쪽에서 모두 UNIQUE를 인지하도록 함

    @Column(name = "invite_code", nullable = false, length = 6, unique = true, updatable = false)
    private String inviteCode;

    // 로비 제목 (이름)
    @Column(name = "title", nullable = false, length = 100)
    private String title;

    // 최대 참여 가능 인원
    @Column(name = "max_players", nullable = false)
    private Integer maxPlayers;

    // 공개 (false) / 비공개 (true) 여부
    // Redis의 lobby:public Set 추가 여부를 결정하는 기준값
    @Column(name = "is_private", nullable = false)
    private Boolean isPrivate;

    // 로비 상태 :
    // - WAITING : 게임 시작 전 대기 중
    // - PLAYING : 게임 진행 중

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private LobbyStatus status = LobbyStatus.WAITING; // 생성 시 항상 WAITING으로 초기화

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 로비 상태를 나타내는 열거형
    // EnumType.STRING으로 저장하여 DB에 "WAITING", "PLAYING" 문자열로 기록
    // ORDINAL 방식은 Enum 순서를 변경하면 데이터 정합성이 깨질 위험이 있어서 사용 X
    public enum LobbyStatus {
        WAITING,
        PLAYING
    }

    // 엔티티 최초 저장 직전에 자동으로 createAt를 현재 시각으로 세팅
    // 애플리케이션 레벨이서 처리하기 때문에 DB의 DEFAULT NOW()와 이중 관리 하지 않아도 됨
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // 방장을 새로운 유저로 교체
    // Lua 스크립트 (leave_lobby.lua)에서 방장 위임이 처리된 후, DB 동기화가 필요할 때 호출
    public void delegateHost(Long newHostUserId) {
        this.hostUserId = newHostUserId;
    }

    // 게임 시작 시에 로비 상태를 PLAYING으로 전환
    public void startGame() {
        this.status = LobbyStatus.PLAYING;
    }

    // 게임 종료 시에 로비 상태를 WAITING으로 복귀
    public void endGame() {
        this.status = LobbyStatus.WAITING;
    }
}