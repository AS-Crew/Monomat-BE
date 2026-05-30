package io.github.ascrew.monomatbe.domain.map.entity;

public enum MapSortType {

    /** 최신순 (updatedAt DESC, 기본값) */
    NEWEST,

    /** 오래된 순 (updatedAt ASC) */
    OLDEST,

    /** 곡 수 많은 순 (numOfSong DESC) */
    MOST_SONGS,

    /** 제목 오름차순 (title ASC) */
    TITLE_ASC
}
