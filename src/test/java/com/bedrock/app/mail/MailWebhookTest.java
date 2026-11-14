package com.bedrock.app.mail;

import org.junit.jupiter.api.Test;

class MailWebhookTest {
    @Test void authenticatesOriginalBytes() { MailWebhookContractChecks.authenticatesOriginalBytes(); }
    @Test void rejectsExpiredAndFutureSignatures() { MailWebhookContractChecks.expiresSignedRequests(); }
    @Test void ignoresTemporaryBouncesAndEngagementEvents() { MailWebhookContractChecks.handlesOnlyPermanentBounces(); }
    @Test void rejectsMalformedEvents() { MailWebhookContractChecks.rejectsMalformedEvents(); }
    @Test void limitsAndErasesRequestBody() { MailWebhookContractChecks.boundsAndErasesBody(); }
}
