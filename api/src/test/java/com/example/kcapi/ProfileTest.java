package com.example.kcapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.kcapi.KeycloakUserRepository.UserRow;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Auth0 に返すプロフィールの形。特に username の扱い。 */
class ProfileTest {

    private static UserRow user(String username) {
        return new UserRow("kc-1", username, "user@atko.email", true, true, "Taro", "Yamada", 0L);
    }

    @Test
    void emailShapedUsernameIsOmitted_becauseAuth0RejectsIt() {
        // Auth0: "username" cannot be an email address.
        Map<String, Object> profile = AuthController.profile(user("user@atko.email"));
        assertFalse(profile.containsKey("username"));
    }

    @Test
    void plainUsernameIsKept() {
        Map<String, Object> profile = AuthController.profile(user("taro.yamada"));
        assertEquals("taro.yamada", profile.get("username"));
    }

    @Test
    void blankOrNullUsernameIsOmitted() {
        assertFalse(AuthController.profile(user("")).containsKey("username"));
        assertFalse(AuthController.profile(user("   ")).containsKey("username"));
        assertFalse(AuthController.profile(user(null)).containsKey("username"));
    }

    @Test
    void otherFieldsAreAlwaysReturned() {
        Map<String, Object> profile = AuthController.profile(user("user@atko.email"));
        assertEquals("kc-1", profile.get("user_id")); // user_id は必須
        assertEquals("user@atko.email", profile.get("email"));
        assertEquals(true, profile.get("email_verified"));
        assertEquals("Taro", profile.get("given_name"));
        assertEquals("Yamada", profile.get("family_name"));
    }

    @Test
    void usernameRule() {
        assertTrue(AuthController.isUsableAsAuth0Username("taro"));
        assertFalse(AuthController.isUsableAsAuth0Username("a@b"));
        assertFalse(AuthController.isUsableAsAuth0Username(null));
    }
}
