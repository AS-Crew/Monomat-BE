package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.youtube.model.YoutubeMetadata;

record PreparedManageItem(
        MapItemPrepareSource source,
        YoutubeMetadata metadata,
        String answersJson,
        String hint,
        int hintTime
) {
}