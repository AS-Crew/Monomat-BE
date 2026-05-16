/**
 * 로비 도메인의 비즈니스 로직 패키지.
 *
 * <p>로비 생성, 입장 사전 검증, 조회, ready 변경, 게임 시작,
 * 강퇴, 퇴장 이벤트 처리, 실시간 알림 등
 * 로비와 관련된 핵심 비즈니스 로직을 담당합니다.</p>
 *
 * <ul>
 *   <li>{@code LobbyCreateService} : 로비 생성 유스케이스</li>
 *   <li>{@code LobbyJoinService} : 초대 코드 기반 로비 입장 사전 검증 유스케이스</li>
 *   <li>{@code LobbyQueryService} : 공개 로비 목록 및 로비 상세 조회 유스케이스</li>
 *   <li>{@code LobbyReadyService} : 로비 ready 상태 변경 유스케이스</li>
 *   <li>{@code LobbyStartService} : 로비 게임 시작 전 검증 및 PLAYING 전환 유스케이스</li>
 *   <li>{@code LobbyKickService} : 로비 유저 강퇴 유스케이스</li>
 *   <li>{@code LobbyLeaveEventHandler} : WebSocket 연결 해제에 따른 로비 퇴장 이벤트 처리</li>
 *   <li>{@code LobbyRealtimeNotifier} : STOMP 기반 로비 실시간 알림 발행</li>
 * </ul>
 */
package io.github.ascrew.monomatbe.domain.lobby.service;