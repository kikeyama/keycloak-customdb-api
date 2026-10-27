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
    void nullNamesAreOmitted_becauseAuth0RejectsNullStrings() {
        // サインアップでは名前を入力しないので、DB では null になる。
        // Auth0: 'Expected type string but found type null' on given_name / family_name
        UserRow noNames = new UserRow("kc-2", "user2@atko.email", "user2@atko.email", false, true, null, null, 0L);
        Map<String, Object> profile = AuthController.profile(noNames);
        assertFalse(profile.containsKey("given_name"));
        assertFalse(profile.containsKey("family_name"));
        assertFalse(profile.containsValue(null)); // どの項目も null を含まない
        assertEquals("kc-2", profile.get("user_id"));
        assertEquals("user2@atko.email", profile.get("email"));
        assertEquals(false, profile.get("email_verified"));
    }

    @Test
    void blankNamesAreOmitted() {
        UserRow blank = new UserRow("kc-3", "x", "x@y.z", true, true, "", "  ", 0L);
        Map<String, Object> profile = AuthController.profile(blank);
        assertFalse(profile.containsKey("given_name"));
        assertFalse(profile.containsKey("family_name"));
    }

    @Test
    void nullEmailIsOmitted() {
        // Keycloak では email は必須ではない
        UserRow noEmail = new UserRow("kc-4", "taro", null, false, true, "Taro", "Yamada", 0L);
        Map<String, Object> profile = AuthController.profile(noEmail);
        assertFalse(profile.containsKey("email"));
        assertFalse(profile.containsValue(null));
    }

    @Test
    void profileNeverContainsNullForAnyCombination() {
        String[] values = {null, "", "  ", "ok", "a@b.c"};
        for (String username : values) {
            for (String email : values) {
                for (String first : values) {
                    for (String last : values) {
                        Map<String, Object> profile = AuthController.profile(
                                new UserRow("kc", username, email, true, true, first, last, null));
                        assertFalse(profile.containsValue(null), "null in profile for " + profile);
                        assertTrue(profile.containsKey("user_id"));
                        assertTrue(profile.containsKey("email_verified"));
                    }
                }
            }
        }
    }

    @Test
    void usernameRule() {
        assertTrue(AuthController.isUsableAsAuth0Username("taro"));
        assertFalse(AuthController.isUsableAsAuth0Username("a@b"));
        assertFalse(AuthController.isUsableAsAuth0Username(null));
    }
}
