package io.github.ascrew.monomatbe.repository;

import io.github.ascrew.monomatbe.dto.LobbyRedisDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class LobbyRepositoryImpl implements LobbyRepository {

    private final StringRedisTemplate redisTemplate;
    private static final Duration LOBBY_TTL = Duration.ofHours(1);

    // 로비 관련 모든 키의 TTL을 1시간으로 연장. 슬라이딩 윈도우 방식 적용
    private void refreshLobbyTtl(String code) {
        redisTemplate.expire(LobbyRedisKeys.getLobbyMetaKey(code), LOBBY_TTL);
        redisTemplate.expire(LobbyRedisKeys.getParticipantsKey(code), LOBBY_TTL);
        redisTemplate.expire(LobbyRedisKeys.getOrderKey(code), LOBBY_TTL);
    }

    // 로비 존재 확인. 로비 메타 정보(Hash) 키 존재 여부 확인 및 유효성 검증
    @Override
    public boolean existsByCode(String code) {
        String key = LobbyRedisKeys.getLobbyMetaKey(code);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    // 유저 참여 여부 확인. Redis Set 자료구조를 이용한 특정 유저의 로비 소속 여부 확인
    @Override
    public boolean isParticipant(String code, String userId) {
        String key = LobbyRedisKeys.getParticipantsKey(code);
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, userId));
    }

    // 로비 설정 정보 저장. DTO를 Map으로 변환하여 Hash 저장 및 초기 TTL 설정
    @Override
    public void saveLobby(LobbyRedisDto lobbyDto) {
        String key = LobbyRedisKeys.getLobbyMetaKey(lobbyDto.getCode());
        Map<String, String> data = new HashMap<>();
        data.put("code", lobbyDto.getCode());
        data.put("host_user_id", lobbyDto.getHostId());
        data.put("title", lobbyDto.getTitle());
        data.put("map_id", String.valueOf(lobbyDto.getMapId()));
        data.put("max_players", String.valueOf(lobbyDto.getMaxPlayers()));
        data.put("is_private", String.valueOf(lobbyDto.getIsPrivate()));
        data.put("status", lobbyDto.getStatus());

        redisTemplate.opsForHash().putAll(key, data);
        refreshLobbyTtl(lobbyDto.getCode());
    }

    // 로비 설정 정보 조회. Redis Hash 데이터를 DTO 객체로 변환하여 반환
    @Override
    public LobbyRedisDto getLobby(String code) {
        String key = LobbyRedisKeys.getLobbyMetaKey(code);
        Map<Object, Object> data = redisTemplate.opsForHash().entries(key);
        
        if (data.isEmpty()) return null;

        return LobbyRedisDto.builder()
                .code((String) data.get("code"))
                .hostId((String) data.get("host_user_id"))
                .title((String) data.get("title"))
                .mapId(data.get("map_id") != null ? Long.parseLong((String) data.get("map_id")) : null)
                .maxPlayers(data.get("max_players") != null ? Integer.parseInt((String) data.get("max_players")) : null)
                .isPrivate(data.get("is_private") != null ? Boolean.parseBoolean((String) data.get("is_private")) : null)
                .status((String) data.get("status"))
                .build();
    }

    // 참여자 입장 처리. Set/List에 유저 추가 및 로비 TTL 연장
    @Override
    public void addParticipant(String code, String userId) {
        String participantsKey = LobbyRedisKeys.getParticipantsKey(code);
        String orderKey = LobbyRedisKeys.getOrderKey(code);

        redisTemplate.opsForSet().add(participantsKey, userId);
        redisTemplate.opsForList().rightPush(orderKey, userId);
        refreshLobbyTtl(code);
    }

    // 참여자 퇴장 처리. 참여자 명단(Set/List)에서 유저 제거 및 로비 TTL 연장
    @Override
    public void removeParticipant(String code, String userId) {
        String participantsKey = LobbyRedisKeys.getParticipantsKey(code);
        String orderKey = LobbyRedisKeys.getOrderKey(code);

        redisTemplate.opsForSet().remove(participantsKey, userId);
        redisTemplate.opsForList().remove(orderKey, 1, userId);
        
        if (Boolean.TRUE.equals(redisTemplate.hasKey(participantsKey))) {
            refreshLobbyTtl(code);
        }
    }

    // 현재 방장 ID 조회. 로비 메타 정보(Hash)에서 호스트 ID 추출 및 TTL 연장
    @Override
    public String getHostId(String code) {
        String key = LobbyRedisKeys.getLobbyMetaKey(code);
        String hostId = (String) redisTemplate.opsForHash().get(key, "host_user_id");
        if (hostId != null) refreshLobbyTtl(code);
        return hostId;
    }

    // 방장 권한 위임. 방장 ID 필드 갱신 및 로비 TTL 연장
    @Override
    public void updateHostId(String code, String nextHostId) {
        String key = LobbyRedisKeys.getLobbyMetaKey(code);
        redisTemplate.opsForHash().put(key, "host_user_id", nextHostId);
        refreshLobbyTtl(code);
    }

    // 현재 참여 인원수 확인. 참여자 명단(Set) 크기 조회
    @Override
    public Long getParticipantCount(String code) {
        String key = LobbyRedisKeys.getParticipantsKey(code);
        return redisTemplate.opsForSet().size(key);
    }

    // 방장 위임 후보 조회. 입장 순서(List)의 0번 인덱스 유저 반환
    @Override
    public String getNextHostCandidate(String code) {
        String key = LobbyRedisKeys.getOrderKey(code);
        return redisTemplate.opsForList().index(key, 0);
    }

    // 공개 로비 목록 노출. 공개 설정된 로비 코드를 인덱스(Set)에 등록
    @Override
    public void addToPublicList(String code) {
        redisTemplate.opsForSet().add(LobbyRedisKeys.getPublicLobbiesKey(), code);
    }

    // [Discovery] 공개 로비 목록 제외. 로비 비공개 전환 또는 삭제 시 인덱스에서 삭제.
    @Override
    public void removeFromPublicList(String code) {
        redisTemplate.opsForSet().remove(LobbyRedisKeys.getPublicLobbiesKey(), code);
    }

    // [Cleanup] 로비 데이터 완전 삭제. 메타 정보, 참여자 명단, 순서 리스트를 Redis에서 즉시 제거.
    @Override
    public void deleteLobby(String code) {
        redisTemplate.delete(java.util.List.of(
                LobbyRedisKeys.getLobbyMetaKey(code),
                LobbyRedisKeys.getParticipantsKey(code),
                LobbyRedisKeys.getOrderKey(code)
        ));
    }
    }
