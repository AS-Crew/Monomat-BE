package io.github.ascrew.monomatbe.repository;

import io.github.ascrew.monomatbe.dto.LobbyRedisDto;

public interface LobbyRepository {
  // 1. 존재 및 참여 확인
  boolean existsByCode(String code);
  boolean isParticipant(String code, String userId);

  // 2. 로비 생성 및 정보 저장
  void saveLobby(LobbyRedisDto lobbyDto); // 로비 메타 정보 저장
  LobbyRedisDto getLobby(String code); // 로비 메타 정보 조회

  // 3. 참여자 관리
  void addParticipant(String code, String userId);
  void removeParticipant(String code, String userId);

  // 4. 방장 및 인원 확인
  String getHostId(String code);
  void updateHostId(String code, String nextHostId);
  Long getParticipantCount(String code);

  // 6. 방장 위임을 위한 대기 순번 조회
  String getNextHostCandidate(String code);

  // 5. 공개 로비 리스트 관리
  void addToPublicList(String code);
  void removeFromPublicList(String code);

  // 7. 로비 완전 삭제
  void deleteLobby(String code);
}