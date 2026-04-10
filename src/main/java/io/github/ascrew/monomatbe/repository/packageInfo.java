package io.github.ascrew.monomatbe.repository;

/*
  실시간 게임 데이터 처리를 위해 Redis와 직접 통신하는 데이터 접근 클래스입니다.

  일반적인 MySQL 연동(Spring Data JPA)과 분리되어 작동하며,
  Redis Lettuce를 활용한 인메모리 데이터 캐싱 및 Pub/Sub 기능을 담당합니다.
  빠른 응답이 필요한 동시 정답자 캐싱이나 게임 상태 동기화 데이터를 영속화하고 관리합니다.
 */