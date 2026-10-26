package com.example.kcapi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class KeycloakPasswordHasherTest {

    private final KeycloakPasswordHasher hasher = new KeycloakPasswordHasher(
            JsonMapper.builder().build(),
            new AppProperties("key", "master", new AppProperties.Hash(7168, 5, 1, 32)));

    @Test
    void roundTrip() {
        var hashed = hasher.hash("correct horse battery staple");
        assertTrue(hasher.verify("correct horse battery staple", hashed.credentialData(), hashed.secretData()));
        assertFalse(hasher.verify("wrong", hashed.credentialData(), hashed.secretData()));
    }

    @Test
    void rejectsOtherAlgorithms() {
        assertFalse(hasher.verify("x", "{\"algorithm\":\"pbkdf2-sha256\",\"hashIterations\":600000}",
                "{\"value\":\"AA==\",\"salt\":\"AA==\"}"));
    }

    // TODO: 実 Keycloak で作ったユーザー (パスワードは既知のテスト用) の credential_data / secret_data を
    // ここに貼って verify が true になることを確認する。Keycloak との互換性を担保するテスト。
}
