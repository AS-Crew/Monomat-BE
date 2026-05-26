package io.github.ascrew.monomatbe.global.common.controller;

import io.github.ascrew.monomatbe.global.common.dto.ServerTimeResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    @GetMapping("/time")
    public ResponseEntity<ServerTimeResponse> getServerTime() {
        return ResponseEntity.ok(new ServerTimeResponse(System.currentTimeMillis()));
    }
}
