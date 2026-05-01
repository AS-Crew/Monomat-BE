/**
 * 로비 관련 컨트롤러 패키지.
 *
 * <p>HTTP REST API와 WebSocket 이벤트 두 가지 방식으로 로비 요청을 처리합니다.
 * <ul>
 *   <li>{@code LobbyController} : HTTP REST API (로비 목록 조회 등)</li>
 *   <li>{@code LobbyEventController} : WebSocket STOMP (로비 생성/변경 이벤트)</li>
 * </ul>
 */
package io.github.ascrew.monomatbe.domain.lobby.controller;