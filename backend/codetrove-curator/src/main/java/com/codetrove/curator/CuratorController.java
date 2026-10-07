package com.codetrove.curator;

import com.codetrove.common.api.ApiResponse;
import com.codetrove.common.api.TraceContext;
import com.codetrove.common.security.AuthenticatedUser;

import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/repositories/{repositoryId}/merge-requests/{iid}/review-findings")
@Validated
class CuratorController {

    private final CuratorQueryService queryService;

    CuratorController(CuratorQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    ApiResponse<CuratorQueryService.ReviewView> find(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable long repositoryId,
        @PathVariable @Min(1) int iid,
        @RequestParam(required = false) String skill,
        @RequestParam(required = false) String severity,
        @RequestParam(required = false) String disposition
    ) {
        return ApiResponse.success(
            queryService.find(repositoryId, iid, user, skill, severity, disposition),
            TraceContext.currentTraceId()
        );
    }
}
