package com.bedrock.app.common;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application*.yml의 키를 Spring Boot · springdoc 메타데이터와 대조한다.
 * Boot 4에서 이름이 바뀐 키(예: spring.session.redis.namespace, server.error.*)는 오류 없이 무시되므로
 * 없는 키와 error 단계로 폐기된 키를 여기서 실패로 잡는다.
 */
class ConfigurationKeysTest {
    /** 메타데이터가 없는 키. 사유를 함께 적는다. */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "bedrock.",               // 앱 자체 키. 읽는 클래스가 생기면 @ConfigurationProperties로 둔다
            "spring.docker.compose."  // developmentOnly 모듈이라 테스트 클래스패스에 메타데이터가 없다
    );

    @Test
    void everyYamlKeyIsKnownAndNotRemoved() throws Exception {
        var metadata = metadata();
        var problems = new ArrayList<String>();
        for (String file : List.of("application.yml", "application-local.yml")) {
            for (String key : yamlKeys(file)) {
                if (ALLOWED_PREFIXES.stream().anyMatch(key::startsWith)) continue;
                var property = metadata.get(key);
                if (property != null) {
                    if ("error".equals(property.deprecationLevel())) {
                        problems.add(file + ": " + key + " 는 폐기됨, 대신 " + property.replacement());
                    }
                    continue;
                }
                if (!underMapProperty(key, metadata)) problems.add(file + ": " + key + " 는 메타데이터에 없음");
            }
        }
        assertThat(problems).isEmpty();
    }

    private static boolean underMapProperty(String key, Map<String, Property> metadata) {
        for (int dot = key.lastIndexOf('.'); dot > 0; dot = key.lastIndexOf('.', dot - 1)) {
            var parent = metadata.get(key.substring(0, dot));
            if (parent != null) return parent.type() != null && parent.type().startsWith("java.util.Map");
        }
        return false;
    }

    private List<String> yamlKeys(String file) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/" + file)) {
            assertThat(input).as(file).isNotNull();
            var keys = new ArrayList<String>();
            for (Object document : new Yaml().loadAll(input)) {
                if (document instanceof Map<?, ?> map) flatten("", map, keys);
            }
            return keys;
        }
    }

    private static void flatten(String prefix, Map<?, ?> map, List<String> keys) {
        for (var entry : map.entrySet()) {
            String key = prefix + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> child) {
                flatten(key + ".", child, keys);
            } else {
                keys.add(key);
            }
        }
    }

    private static Map<String, Property> metadata() throws Exception {
        var json = JsonMapper.builder().build();
        var resolver = new PathMatchingResourcePatternResolver();
        var properties = new HashMap<String, Property>();
        for (String pattern : List.of("classpath*:META-INF/spring-configuration-metadata.json",
                "classpath*:META-INF/additional-spring-configuration-metadata.json")) {
            for (Resource resource : resolver.getResources(pattern)) {
                try (InputStream input = resource.getInputStream()) {
                    JsonNode root = json.readTree(input);
                    for (JsonNode node : root.path("properties")) {
                        String name = node.path("name").asString();
                        var previous = properties.get(name);
                        String type = node.hasNonNull("type") ? node.get("type").asString()
                                : previous == null ? null : previous.type();
                        JsonNode deprecation = node.path("deprecation");
                        String level = deprecation.isMissingNode() ? (previous == null ? null : previous.deprecationLevel())
                                : deprecation.path("level").asString("warning");
                        String replacement = deprecation.path("replacement").asString(
                                previous == null ? "" : previous.replacement());
                        properties.put(name, new Property(type, level, replacement));
                    }
                }
            }
        }
        assertThat(properties).as("Boot 메타데이터를 찾지 못함").hasSizeGreaterThan(500);
        return new TreeMap<>(properties);
    }

    private record Property(String type, String deprecationLevel, String replacement) {
    }
}
