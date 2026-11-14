package com.bedrock.app.crypto;

import org.junit.jupiter.api.Test;

class UserCryptoTest {
    @Test void encryptionAuthenticatesContentAndUser() { CryptoContractChecks.envelopeIntegrity(); }
    @Test void deletingKeysPreventsFurtherUse() { CryptoContractChecks.keyLifetime(); }
    @Test void vaultCreatesKeysWithoutOverwritingExistingKeys() { CryptoContractChecks.vaultCreation(); }
    @Test void vaultDecryptsKeysOnlyInTheOwnerContext() { CryptoContractChecks.vaultLoad(); }
    @Test void vaultDestroysAllKeyVersionsAndRetainsDeletionMarker() { CryptoContractChecks.vaultDeletion(); }
    @Test void vaultFailuresNeverExposeSecretsOrPretendSuccess() { CryptoContractChecks.vaultFailure(); }
    @Test void vaultConfigurationRequiresSecureTransport() { CryptoContractChecks.configuration(); }
}
