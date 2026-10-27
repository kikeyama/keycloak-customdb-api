package com.example.kcapi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keycloak の Argon2PasswordHashProvider と同じ方式でハッシュの生成・検証を行う。
 * 検証はレコードに保存されたパラメータ(type/version/hashLength/memory/parallelism/iterations)を使う。
 */
@Component
public class KeycloakPasswordHasher {

    private static final Logger log = LoggerFactory.getLogger(KeycloakPasswordHasher.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JsonMapper mapper;
    private final AppProperties.Hash defaults;

    public KeycloakPasswordHasher(JsonMapper mapper, AppProperties props) {
        this.mapper = mapper;
        this.defaults = props.hash();
    }

    /** credential テーブルに保存する credential_data / secret_data の JSON。 */
    public record Hashed(String credentialData, String secretData) {
    }

    public Hashed hash(String rawPassword) {
        byte[] salt = new byte[16]; // Keycloak の Salt.generateSalt() と同じ 16 バイト
        RANDOM.nextBytes(salt);
        String value = compute(rawPassword, salt, "id", "1.3", defaults.hashLength(),
                defaults.parallelism(), defaults.memoryKb(), defaults.iterations());

        ObjectNode credentialData = mapper.createObjectNode();
        credentialData.put("hashIterations", defaults.iterations());
        credentialData.put("algorithm", "argon2");
        ObjectNode params = credentialData.putObject("additionalParameters");
        params.putArray("hashLength").add(Integer.toString(defaults.hashLength()));
        params.putArray("memory").add(Integer.toString(defaults.memoryKb()));
        params.putArray("type").add("id");
        params.putArray("version").add("1.3");
        params.putArray("parallelism").add(Integer.toString(defaults.parallelism()));

        ObjectNode secretData = mapper.createObjectNode();
        secretData.put("value", value);
        secretData.put("salt", Base64.getEncoder().encodeToString(salt));
        secretData.putObject("additionalParameters");

        return new Hashed(credentialData.toString(), secretData.toString());
    }

    public boolean verify(String rawPassword, String credentialDataJson, String secretDataJson) {
        return verifyDetailed(rawPassword, credentialDataJson, secretDataJson) == Result.MATCH;
    }

    /** 検証の結果。ログで「パスワード不一致」と「検証できなかった」を区別するために使う。 */
    public enum Result {
        MATCH,
        MISMATCH,
        UNSUPPORTED_ALGORITHM,
        /** 保存されたデータが読めない (JSON の形式、パラメータ欠落など) */
        INVALID_RECORD
    }

    public Result verifyDetailed(String rawPassword, String credentialDataJson, String secretDataJson) {
        try {
            JsonNode credentialData = mapper.readTree(credentialDataJson);
            JsonNode secretData = mapper.readTree(secretDataJson);

            String algorithm = credentialData.path("algorithm").asText();
            if (!"argon2".equals(algorithm)) {
                log.warn("Unsupported password algorithm: {}", algorithm);
                return Result.UNSUPPORTED_ALGORITHM;
            }

            // 書き出し時のキーは additionalParameters。読み込み用のコンストラクタは algorithmData。両方に対応する。
            JsonNode params = credentialData.has("additionalParameters")
                    ? credentialData.get("additionalParameters")
                    : credentialData.path("algorithmData");

            byte[] salt = Base64.getMimeDecoder().decode(secretData.path("salt").asText());
            String actual = compute(rawPassword, salt,
                    first(params, "type"),
                    first(params, "version"),
                    Integer.parseInt(first(params, "hashLength")),
                    Integer.parseInt(first(params, "parallelism")),
                    Integer.parseInt(first(params, "memory")),
                    credentialData.path("hashIterations").asInt());

            boolean match = MessageDigest.isEqual(
                    actual.getBytes(StandardCharsets.UTF_8),
                    secretData.path("value").asText().getBytes(StandardCharsets.UTF_8));
            return match ? Result.MATCH : Result.MISMATCH;
        } catch (RuntimeException e) { // Jackson 3 の JacksonException は非検査例外
            log.warn("Could not verify password: {}", e.toString());
            return Result.INVALID_RECORD;
        }
    }

    private static String first(JsonNode params, String key) {
        JsonNode node = params.path(key);
        String value = node.isArray() ? node.path(0).asText(null) : node.asText(null);
        if (value == null) {
            throw new IllegalArgumentException("Missing argon2 parameter: " + key);
        }
        return value;
    }

    private static String compute(String rawPassword, byte[] salt, String type, String version,
                                  int hashLength, int parallelism, int memoryKb, int iterations) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(typeValue(type))
                .withVersion(versionValue(version))
                .withSalt(salt)
                .withParallelism(parallelism)
                .withMemoryAsKB(memoryKb)
                .withIterations(iterations)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] result = new byte[hashLength];
        generator.generateBytes(rawPassword.toCharArray(), result);
        return Base64.getEncoder().encodeToString(result);
    }

    private static int typeValue(String type) {
        return switch (type) {
            case "id" -> Argon2Parameters.ARGON2_id;
            case "d" -> Argon2Parameters.ARGON2_d;
            case "i" -> Argon2Parameters.ARGON2_i;
            default -> throw new IllegalArgumentException("Unknown argon2 type: " + type);
        };
    }

    private static int versionValue(String version) {
        return switch (version) {
            case "1.3" -> Argon2Parameters.ARGON2_VERSION_13;
            case "1.0" -> Argon2Parameters.ARGON2_VERSION_10;
            default -> throw new IllegalArgumentException("Unknown argon2 version: " + version);
        };
    }
}
