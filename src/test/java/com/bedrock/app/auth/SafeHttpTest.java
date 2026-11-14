package com.bedrock.app.auth;

import org.junit.jupiter.api.Test;

class SafeHttpTest {
    @Test void consumesChunkedBodyWithinLimit() throws Exception { SafeHttpContractChecks.receivesBoundedChunks(); }
    @Test void abortsOversizedBodyBeforeCompletion() throws Exception { SafeHttpContractChecks.rejectsOverflow(); }
    @Test void timesOutAfterHeadersIfBodyStalls() throws Exception { SafeHttpContractChecks.cancelsStalledBody(); }
    @Test void cancelsTransportWhenCallerIsInterrupted() throws Exception { SafeHttpContractChecks.cancelsInterruptedCall(); }
    @Test void doesNotExposeProviderResponseInException() throws Exception { SafeHttpContractChecks.removesTransportDetails(); }
}
