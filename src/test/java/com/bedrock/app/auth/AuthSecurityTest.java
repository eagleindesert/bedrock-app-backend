package com.bedrock.app.auth;

import org.junit.jupiter.api.Test;

class AuthSecurityTest {
    @Test void passwordPolicyAndHashing() { AuthSecurityContractChecks.passwords(); }
    @Test void transientTokensStayEncrypted() { AuthSecurityContractChecks.flowCipher(); }
    @Test void identityTokensRequireValidSignatureAndClaims() throws Exception { AuthSecurityContractChecks.tokenVerification(); }
    @Test void firstLinkEmailRules() throws Exception { AuthSecurityContractChecks.authoritativeEmails(); }
    @Test void redirectAllowlist() throws Exception { AuthSecurityContractChecks.redirects(); }
}
