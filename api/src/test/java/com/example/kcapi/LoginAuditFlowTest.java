package com.example.kcapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Login / API キーの検証を、コントローラーとフィルターを通して行い、監査ログの内容を確かめる。 */
class LoginAuditFlowTest {

    private static final String API_KEY = "expected-key";

    private final KeycloakUserRepository users = mock(KeycloakUserRepository.class);
    private final KeycloakPasswordHasher hasher = new KeycloakPasswordHasher(
            JsonMapper.builder().build(),
            new AppProperties(API_KEY, "master", new AppProperties.Hash(7168, 5, 1, 32)));

    private MockMvc mvc;
    private ListAppender<ILoggingEvent> appender;
    private Logger audit;
    private KeycloakPasswordHasher.Hashed stored;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties(API_KEY, "master", new AppProperties.Hash(7168, 5, 1, 32));
        mvc = standaloneSetup(new AuthController(users, hasher)).addFilters(new ApiKeyFilter(props)).build();

        stored = hasher.hash("correct-password");
        var user = new KeycloakUserRepository.UserRow("kc-1", "a@example.com", "a@example.com",
                true, true, "A", "B", 0L);
        when(users.findByEmail("a@example.com")).thenReturn(Optional.of(user));
        when(users.findByEmail("none@example.com")).thenReturn(Optional.empty());
        when(users.findPasswordCredential("kc-1"))
                .thenReturn(Optional.of(new KeycloakUserRepository.CredentialRow(
                        stored.credentialData(), stored.secretData())));

        audit = (Logger) LoggerFactory.getLogger("audit");
        appender = new ListAppender<>();
        appender.start();
        audit.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        audit.detachAppender(appender);
    }

    private List<String> logs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private int login(String key, String email, String password) throws Exception {
        var request = post("/api/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .header("X-Forwarded-For", "203.0.113.9");
        if (key != null) {
            request.header("X-API-Key", key);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void successIsLoggedWithUsernameAndUserId() throws Exception {
        assertEquals(200, login(API_KEY, "a@example.com", "correct-password"));
        assertEquals(List.of("event=login result=success username=a@example.com user_id=kc-1 client_ip=203.0.113.9"),
                logs());
    }

    @Test
    void wrongPasswordIsLoggedAsPasswordMismatch_butResponseDoesNotSayWhy() throws Exception {
        assertEquals(401, login(API_KEY, "a@example.com", "wrong"));
        assertEquals(List.of("event=login result=failure reason=PASSWORD_MISMATCH username=a@example.com client_ip=203.0.113.9"),
                logs());
    }

    @Test
    void unknownUserIsLoggedAsUserNotFound_andResponseStatusIsTheSame() throws Exception {
        int unknownUser = login(API_KEY, "none@example.com", "whatever");
        int wrongPassword = login(API_KEY, "a@example.com", "wrong");
        assertEquals(401, unknownUser);
        assertEquals(unknownUser, wrongPassword); // 理由を区別できない (アカウント列挙の防止)
        assertTrue(logs().get(0).contains("reason=USER_NOT_FOUND username=none@example.com"));
    }

    @Test
    void wrongApiKeyIsLoggedAndNeverReachesTheController() throws Exception {
        assertEquals(401, login("not-the-key", "a@example.com", "correct-password"));
        assertEquals(List.of("event=api_key result=rejected reason=API_KEY_MISMATCH method=POST path=/api/login client_ip=203.0.113.9"),
                logs());
        assertFalse(logs().get(0).contains("not-the-key"));
    }

    @Test
    void missingApiKeyIsLogged() throws Exception {
        assertEquals(401, login(null, "a@example.com", "correct-password"));
        assertTrue(logs().get(0).contains("reason=API_KEY_MISSING"));
    }

    @Test
    void passwordNeverAppearsInAnyLogLine() throws Exception {
        login(API_KEY, "a@example.com", "correct-password");
        login(API_KEY, "a@example.com", "wrong-secret-pw");
        login("bad-key", "a@example.com", "another-secret-pw");
        for (String line : logs()) {
            assertFalse(line.contains("correct-password"));
            assertFalse(line.contains("wrong-secret-pw"));
            assertFalse(line.contains("another-secret-pw"));
        }
    }
}
