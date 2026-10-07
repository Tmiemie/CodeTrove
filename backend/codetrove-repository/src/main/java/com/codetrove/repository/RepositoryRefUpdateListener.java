package com.codetrove.repository;

import java.util.List;

/** Listener boundary for modules reacting after successful Git reference updates. */
public interface RepositoryRefUpdateListener {

    void afterRefsUpdated(long repositoryId, List<RepositoryRefUpdate> updates);
}
