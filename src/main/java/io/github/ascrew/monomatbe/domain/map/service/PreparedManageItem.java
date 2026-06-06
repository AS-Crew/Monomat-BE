package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.map.dto.ManageMapItemRequest;
import io.github.ascrew.monomatbe.domain.youtube.model.YoutubeMetadata;

record PreparedManageItem(
        ManageMapItemRequest request,
        YoutubeMetadata metadata,
        String answersJson,
        String hint,
        int hintTime
) {
}