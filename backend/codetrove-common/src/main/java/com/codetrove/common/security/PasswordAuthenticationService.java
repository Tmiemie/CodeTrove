package com.codetrove.common.security;

public interface PasswordAuthenticationService {

    AuthenticatedUser authenticate(String username, String password);
}
