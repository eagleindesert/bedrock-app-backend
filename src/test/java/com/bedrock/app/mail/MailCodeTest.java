package com.bedrock.app.mail;

import org.junit.jupiter.api.Test;

class MailCodeTest {
    @Test void hmacBindsCodeToChallengeEmailSessionAndPurpose() { MailCodeContractChecks.hmacBindsEveryPartOfTheChallenge(); }
    @Test void codeExpiresAfterTenMinutesAndCanOnlyBeConsumedOnce() { MailCodeContractChecks.expiryAndSingleUse(); }
    @Test void fiveFailuresInvalidateTheChallenge() { MailCodeContractChecks.fiveFailuresConsumeChallenge(); }
    @Test void resendRequiresSixtySecondsAndAnActiveChallenge() { MailCodeContractChecks.resendBoundaries(); }
    @Test void oneHundredFailuresBlockAddressForTwentyFourHours() { MailCodeContractChecks.addressLockoutAndBounce(); }
    @Test void missingCodeSecretNeverFallsBackToInsecureStorage() { MailCodeContractChecks.missingSecretFailsClosed(); }
}
