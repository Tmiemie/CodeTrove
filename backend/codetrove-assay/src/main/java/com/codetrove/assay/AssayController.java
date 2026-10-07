package com.codetrove.assay;

import com.codetrove.common.api.ApiResponse;
import com.codetrove.common.api.TraceContext;
import com.codetrove.common.security.AuthenticatedUser;

import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/repositories/{repositoryId}/merge-requests/{iid}/test-report")
@Validated
class AssayController {

    private final AssayQueryService queryService;

    AssayController(AssayQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    ApiResponse<AssayQueryService.ReportView> find(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable long repositoryId,
        @PathVariable @Min(1) int iid
    ) {
        return ApiResponse.success(
            queryService.find(repositoryId, iid, user),
            TraceContext.currentTraceId()
        );
    }
}
