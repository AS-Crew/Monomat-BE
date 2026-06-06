package io.github.ascrew.monomatbe.domain.map.controller;

import io.github.ascrew.monomatbe.domain.map.service.MapManageService;
import io.github.ascrew.monomatbe.domain.map.service.MapService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MapController.class)
@TestPropertySource(properties = {
        "auth.jwt.secret=01234567890123456789012345678901"
})
class MapManageControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MapService mapService;

    @MockitoBean
    private MapManageService mapManageService;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void updateManagedMap_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(put("/api/maps/{mapId}/manage", 1L)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "J-POP 퀴즈",
                                  "description": "J-POP 중심 퀴즈 맵",
                                  "category": "J-POP",
                                  "isPublic": false,
                                  "items": [],
                                  "deletedItemIds": []
                                }
                                """))
                .andExpect(status().isUnauthorized());

        verify(mapManageService, never()).updateManagedMap(anyLong(), any(), any());
    }
}