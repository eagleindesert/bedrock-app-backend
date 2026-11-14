package com.bedrock.app.crypto.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** 환경 변수 BEDROCK_CRYPTO_VAULT_*로 설정한다. 자격 증명을 toString에 노출하지 않는다. */
@ConfigurationProperties("bedrock.crypto.vault")
public class VaultCryptoProperties {
    private URI address;
    private String token;
    private String transitMount = "transit";
    private String keyMount = "bedrock-keys";
    private Duration timeout = Duration.ofSeconds(5);

    public URI getAddress() { return address; }
    public void setAddress(URI address) { this.address = address; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getTransitMount() { return transitMount; }
    public void setTransitMount(String transitMount) { this.transitMount = transitMount; }
    public String getKeyMount() { return keyMount; }
    public void setKeyMount(String keyMount) { this.keyMount = keyMount; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }

    public void validate() {
        if (address == null || !"https".equals(address.getScheme()) || address.getHost() == null
                || address.getUserInfo() != null || address.getQuery() != null || address.getFragment() != null
                || !(address.getPath().isEmpty() || "/".equals(address.getPath()))
                || token == null || token.isBlank() || token.contains("\r") || token.contains("\n")
                || !validSegment(transitMount) || !validSegment(keyMount)
                || timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Vault 암호화 설정을 확인해 주세요.");
        }
    }

    public static boolean validSegment(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}");
    }
}
