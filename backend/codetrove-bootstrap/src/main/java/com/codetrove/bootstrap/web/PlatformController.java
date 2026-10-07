package com.codetrove.bootstrap.web;

import java.time.Instant;

import com.codetrove.common.api.ApiResponse;
import com.codetrove.common.api.TraceContext;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {

    private final JdbcTemplate jdbcTemplate;

    public PlatformController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/status")
    ApiResponse<PlatformStatus> status() {
        String baseline = jdbcTemplate.queryForObject(
            "SELECT metadata_value FROM codetrove_platform_metadata WHERE metadata_key = ?",
            String.class,
            "schema_baseline"
        );
        return ApiResponse.success(
            new PlatformStatus("CodeTrove", "UP", baseline, Instant.now()),
            TraceContext.currentTraceId()
        );
    }

    public record PlatformStatus(String name, String status, String schemaBaseline, Instant timestamp) {
    }
}
