package com.codetrove.mergerequest;

import java.util.LinkedHashMap;
import java.util.Map;

import com.codetrove.eventing.OutboxService;

import org.springframework.stereotype.Service;

@Service
class MergeRequestEventService {

    private final OutboxService outboxService;

    MergeRequestEventService(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    void created(MergeRequestRecord mergeRequest) {
        Map<String, Object> data = base(mergeRequest);
        data.put("source_branch", mergeRequest.sourceBranch());
        data.put("target_branch", mergeRequest.targetBranch());
        data.put("base_commit", mergeRequest.baseCommit());
        data.put("head_commit", mergeRequest.headCommit());
        data.put("author_id", Long.toString(mergeRequest.authorId()));
        append("mr.created", mergeRequest, data);
    }

    void headUpdated(
        MergeRequestRecord mergeRequest,
        String previousHead,
        int changeSequence
    ) {
        Map<String, Object> data = base(mergeRequest);
        data.put("previous_head_commit", previousHead);
        data.put("head_commit", mergeRequest.headCommit());
        data.put("base_commit", mergeRequest.baseCommit());
        data.put("change_sequence", changeSequence);
        append("mr.head-updated", mergeRequest, data);
    }

    void closed(MergeRequestRecord mergeRequest, long closedBy) {
        Map<String, Object> data = base(mergeRequest);
        data.put("head_commit", mergeRequest.headCommit());
        data.put("closed_by", Long.toString(closedBy));
        append("mr.closed", mergeRequest, data);
    }

    void merged(
        MergeRequestRecord mergeRequest,
        String sourceHead,
        String targetBefore,
        long mergedBy
    ) {
        Map<String, Object> data = base(mergeRequest);
        data.put("source_head_commit", sourceHead);
        data.put("target_before_commit", targetBefore);
        data.put("merge_commit", mergeRequest.mergeCommit());
        data.put("merged_by", Long.toString(mergedBy));
        data.put("strategy", "MERGE_COMMIT");
        append("mr.merged", mergeRequest, data);
    }

    private Map<String, Object> base(MergeRequestRecord mergeRequest) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("repository_id", Long.toString(mergeRequest.repositoryId()));
        data.put("mr_id", Long.toString(mergeRequest.id()));
        data.put("mr_iid", mergeRequest.iid());
        return data;
    }

    private void append(
        String eventType,
        MergeRequestRecord mergeRequest,
        Map<String, Object> data
    ) {
        outboxService.append(
            eventType,
            "MERGE_REQUEST",
            mergeRequest.id(),
            mergeRequest.version(),
            OutboxService.MR_EVENTS_TOPIC,
            mergeRequest.repositoryId() + ":" + mergeRequest.iid(),
            "codetrove-merge-request",
            data
        );
    }
}
