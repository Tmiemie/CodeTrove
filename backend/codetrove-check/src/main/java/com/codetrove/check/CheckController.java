package com.codetrove.check;

import java.util.List;

import com.codetrove.common.api.ApiResponse;
import com.codetrove.common.api.TraceContext;
import com.codetrove.common.exception.BusinessException;
import com.codetrove.common.exception.ErrorCode;
import com.codetrove.common.security.AuthenticatedUser;
import com.codetrove.repository.RepositoryAccessService;
import com.codetrove.repository.RepositoryPermission;

import jakarta.validation.constraints.Min;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/repositories/{repositoryId}/merge-requests/{iid}/checks")
@Validated
class CheckController {

    private final RepositoryAccessService repositoryAccessService;
    private final JdbcTemplate jdbcTemplate;
    private final CheckGateService checkGateService;

    CheckController(
        RepositoryAccessService repositoryAccessService,
        JdbcTemplate jdbcTemplate,
        CheckGateService checkGateService
    ) {
        this.repositoryAccessService = repositoryAccessService;
        this.jdbcTemplate = jdbcTemplate;
        this.checkGateService = checkGateService;
    }

    @GetMapping
    ApiResponse<CheckResponse> find(
        @AuthenticationPrincipal AuthenticatedUser user,
        @PathVariable long repositoryId,
        @PathVariable @Min(1) int iid,
        @RequestParam(defaultValue = "false") boolean includeHistory
    ) {
        repositoryAccessService.require(repositoryId, user, RepositoryPermission.READ);
        Long mergeRequestId = jdbcTemplate.query(
            "SELECT id FROM codetrove_merge_request WHERE repository_id = ? AND iid = ?",
            (resultSet, rowNumber) -> resultSet.getLong("id"),
            repositoryId,
            iid
        ).stream().findFirst().orElseThrow(
            () -> new BusinessException(ErrorCode.MERGE_REQUEST_NOT_FOUND)
        );
        List<CheckGateService.CheckSuiteView> suites = checkGateService.findSuites(
            mergeRequestId,
            includeHistory
        );
        CheckGateService.CheckSuiteView current = suites.stream()
            .filter(CheckGateService.CheckSuiteView::current)
            .findFirst()
            .orElse(null);
        List<CheckGateService.CheckSuiteView> history = includeHistory
            ? suites.stream().filter(suite -> !suite.current()).toList()
            : List.of();
        return ApiResponse.success(
            new CheckResponse(current, history),
            TraceContext.currentTraceId()
        );
    }

    record CheckResponse(
        CheckGateService.CheckSuiteView current,
        List<CheckGateService.CheckSuiteView> history
    ) {
    }
}
