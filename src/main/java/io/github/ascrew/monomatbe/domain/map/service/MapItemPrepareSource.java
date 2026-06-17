package io.github.ascrew.monomatbe.domain.map.service;

import java.util.List;

record MapItemPrepareSource(
        Long id,
        Integer orderNum,
        String youtubeUrl,
        Integer startTime,
        List<String> answers,
        String hint,
        Integer hintTime
) {
}