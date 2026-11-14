package com.bedrock.app.auth.security;

public interface CompromisedPasswordLookup {
    boolean isCompromised(String password);
}
