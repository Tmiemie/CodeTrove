package com.codetrove.repository;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class RepositoryAuthorizationService {

    private static final Map<RepositoryRole, Set<RepositoryPermission>> ROLE_PERMISSIONS = permissionsByRole();

    public boolean isAllowed(RepositoryRole role, RepositoryPermission permission) {
        return role != null && ROLE_PERMISSIONS.getOrDefault(role, Set.of()).contains(permission);
    }

    private static Map<RepositoryRole, Set<RepositoryPermission>> permissionsByRole() {
        Map<RepositoryRole, Set<RepositoryPermission>> permissions = new EnumMap<>(RepositoryRole.class);
        permissions.put(RepositoryRole.OWNER, EnumSet.allOf(RepositoryPermission.class));
        permissions.put(RepositoryRole.MAINTAINER, EnumSet.of(
            RepositoryPermission.READ,
            RepositoryPermission.CREATE_MERGE_REQUEST,
            RepositoryPermission.COMMENT,
            RepositoryPermission.PUSH,
            RepositoryPermission.MERGE,
            RepositoryPermission.MODIFY_RULES
        ));
        permissions.put(RepositoryRole.DEVELOPER, EnumSet.of(
            RepositoryPermission.READ,
            RepositoryPermission.CREATE_MERGE_REQUEST,
            RepositoryPermission.COMMENT,
            RepositoryPermission.PUSH,
            RepositoryPermission.MERGE
        ));
        permissions.put(RepositoryRole.REPORTER, EnumSet.of(
            RepositoryPermission.READ,
            RepositoryPermission.CREATE_MERGE_REQUEST,
            RepositoryPermission.COMMENT
        ));
        return Map.copyOf(permissions);
    }
}
