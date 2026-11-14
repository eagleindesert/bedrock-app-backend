package com.bedrock.app.crypto.config;

import com.bedrock.app.common.port.UserCryptoPort;
import com.bedrock.app.crypto.key.VaultUserDataKeyProvider;
import com.bedrock.app.crypto.service.EnvelopeUserCrypto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "bedrock.crypto.provider", havingValue = "vault")
@EnableConfigurationProperties(VaultCryptoProperties.class)
public class UserCryptoConfiguration {
    @Bean
    public UserCryptoPort envelopeUserCrypto(VaultCryptoProperties properties, ObjectMapper mapper,
            @Value("${bedrock.kms.key-id}") String keyId) {
        properties.validate();
        if (!VaultCryptoProperties.validSegment(keyId)) {
            throw new IllegalArgumentException("KMS 키 식별자를 확인해 주세요.");
        }
        var client = HttpClient.newBuilder().connectTimeout(properties.getTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new EnvelopeUserCrypto(new VaultUserDataKeyProvider(client, mapper, properties, keyId));
    }
}
