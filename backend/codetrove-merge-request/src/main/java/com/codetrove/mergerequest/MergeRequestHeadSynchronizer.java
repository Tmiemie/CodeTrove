package com.codetrove.mergerequest;

import java.util.List;

import com.codetrove.repository.RepositoryRefUpdate;
import com.codetrove.repository.RepositoryRefUpdateListener;

import org.eclipse.jgit.lib.Constants;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class MergeRequestHeadSynchronizer implements RepositoryRefUpdateListener {

    private final MergeRequestHistoryRepository historyRepository;
    private final MergeRequestRepository mergeRequestRepository;
    private final MergeRequestEventService eventService;

    MergeRequestHeadSynchronizer(
        MergeRequestHistoryRepository historyRepository,
        MergeRequestRepository mergeRequestRepository,
        MergeRequestEventService eventService
    ) {
        this.historyRepository = historyRepository;
        this.mergeRequestRepository = mergeRequestRepository;
        this.eventService = eventService;
    }

    @Override
    @Transactional
    public void afterRefsUpdated(long repositoryId, List<RepositoryRefUpdate> updates) {
        for (RepositoryRefUpdate update : updates) {
            if (update.deletion() || !update.refName().startsWith(Constants.R_HEADS)) {
                continue;
            }
            String sourceBranch = update.refName().substring(Constants.R_HEADS.length());
            for (Long mergeRequestId : historyRepository.findOpenMergeRequestIds(
                repositoryId,
                sourceBranch,
                update.newObjectId()
            )) {
                MergeRequestHistoryRepository.HeadUpdate headUpdate = historyRepository.updateHeadAndRecord(
                    mergeRequestId,
                    update.newObjectId()
                );
                if (headUpdate != null) {
                    MergeRequestRecord mergeRequest = mergeRequestRepository.findById(mergeRequestId)
                        .orElseThrow(() -> new IllegalStateException("Updated merge request disappeared"));
                    eventService.headUpdated(
                        mergeRequest,
                        headUpdate.previousHead(),
                        headUpdate.sequence()
                    );
                }
            }
        }
    }
}
