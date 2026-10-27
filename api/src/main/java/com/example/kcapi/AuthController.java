package com.example.kcapi;

import com.example.kcapi.KeycloakUserRepository.UserRow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Auth0 カスタムデータベースのアクションスクリプトに 1:1 で対応するエンドポイント。
 * Login / Verify / Create / Get User / Delete / Change Password / Change Email。
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record VerifyRequest(@NotBlank String email) {
    }

    public record CreateUserRequest(@NotBlank @Email String email, @NotBlank String password,
                                    String givenName, String familyName, Boolean emailVerified) {
    }

    public record ChangePasswordRequest(@NotBlank String email, @NotBlank String newPassword) {
    }

    public record ChangeEmailRequest(@NotBlank String email, @NotBlank @Email String newEmail, Boolean emailVerified) {
    }

    private final KeycloakUserRepository users;
    private final KeycloakPasswordHasher hasher;

    public AuthController(KeycloakUserRepository users, KeycloakPasswordHasher hasher) {
        this.users = users;
        this.hasher = hasher;
    }

    /** Login: 認証に成功したらプロフィールを返す。失敗は 401。 */
    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String ip = AuditLog.clientIp(http);

        Optional<UserRow> found = users.findByEmail(request.email());
        if (found.isEmpty()) {
            return loginFailed(request.email(), AuditLog.Reason.USER_NOT_FOUND, ip);
        }
        UserRow user = found.get();
        if (!user.enabled()) {
            return loginFailed(request.email(), AuditLog.Reason.USER_DISABLED, ip);
        }

        Optional<KeycloakUserRepository.CredentialRow> credential = users.findPasswordCredential(user.id());
        if (credential.isEmpty()) {
            return loginFailed(request.email(), AuditLog.Reason.NO_PASSWORD_CREDENTIAL, ip);
        }

        KeycloakPasswordHasher.Result result = hasher.verifyDetailed(
                request.password(), credential.get().credentialData(), credential.get().secretData());
        switch (result) {
            case MATCH -> {
                AuditLog.loginSuccess(request.email(), user.id(), ip);
                return profile(user);
            }
            case MISMATCH -> {
                return loginFailed(request.email(), AuditLog.Reason.PASSWORD_MISMATCH, ip);
            }
            case UNSUPPORTED_ALGORITHM -> {
                return loginFailed(request.email(), AuditLog.Reason.UNSUPPORTED_ALGORITHM, ip);
            }
            default -> {
                return loginFailed(request.email(), AuditLog.Reason.INVALID_RECORD, ip);
            }
        }
    }

    /**
     * 失敗の理由はログにだけ出し、レスポンスは常に同じ 401 にする。
     * 理由をレスポンスに含めると、ユーザーの存在を外部から調べられてしまう (アカウント列挙)。
     */
    private static Map<String, Object> loginFailed(String username, AuditLog.Reason reason, String ip) {
        AuditLog.loginFailure(username, reason, ip);
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
    }

    /** Verify: メールアドレスを検証済みにする。 */
    @PostMapping("/verify")
    public Map<String, Object> verify(@Valid @RequestBody VerifyRequest request, HttpServletRequest http) {
        UserRow user = requireUser("verify", request.email(), http);
        users.markEmailVerified(user.id());
        AuditLog.operation("verify", "success", request.email(), AuditLog.clientIp(http));
        return profile(requireUser("verify", request.email(), http));
    }

    /** Create: ユーザーを Keycloak のスキーマで作成する。既存なら 409。 */
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateUserRequest request, HttpServletRequest http) {
        String ip = AuditLog.clientIp(http);
        if (users.findByEmail(request.email()).isPresent()) {
            AuditLog.operation("create_user", "failure:user_exists", request.email(), ip);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "user already exists");
        }
        UserRow user = users.createUser(request.email(), request.givenName(), request.familyName(),
                Boolean.TRUE.equals(request.emailVerified()), hasher.hash(request.password()));
        AuditLog.operation("create_user", "success", request.email(), ip);
        return profile(user);
    }

    /** Get User: メールアドレスでユーザーを取得する。いなければ 404。 */
    @GetMapping("/users")
    public Map<String, Object> get(@RequestParam String email, HttpServletRequest http) {
        return profile(requireUser("get_user", email, http));
    }

    /** Delete: ユーザー ID で削除する。 */
    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, HttpServletRequest http) {
        String ip = AuditLog.clientIp(http);
        if (!users.deleteUser(id)) {
            AuditLog.operation("delete_user", "failure:user_not_found", id, ip);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        }
        AuditLog.operation("delete_user", "success", id, ip);
    }

    /** Change Password: パスワードのハッシュを入れ替える。 */
    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request, HttpServletRequest http) {
        UserRow user = requireUser("change_password", request.email(), http);
        users.replacePassword(user.id(), hasher.hash(request.newPassword()));
        AuditLog.operation("change_password", "success", request.email(), AuditLog.clientIp(http));
    }

    /** Change Email: メールアドレス (と、旧メールと同じなら username) を更新する。 */
    @PostMapping("/change-email")
    public Map<String, Object> changeEmail(@Valid @RequestBody ChangeEmailRequest request, HttpServletRequest http) {
        String ip = AuditLog.clientIp(http);
        UserRow user = requireUser("change_email", request.email(), http);
        if (!request.email().equalsIgnoreCase(request.newEmail())
                && users.findByEmail(request.newEmail()).isPresent()) {
            AuditLog.operation("change_email", "failure:email_in_use", request.email(), ip);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already in use");
        }
        users.changeEmail(user.id(), user.email(), request.newEmail(), Boolean.TRUE.equals(request.emailVerified()));
        AuditLog.operation("change_email", "success", request.email(), ip);
        return profile(users.findById(user.id()).orElseThrow());
    }

    /** ユーザーがいなければ 404。いなかったことを監査ログに残す。 */
    private UserRow requireUser(String event, String email, HttpServletRequest http) {
        return users.findByEmail(email).orElseThrow(() -> {
            AuditLog.operation(event, "failure:user_not_found", email, AuditLog.clientIp(http));
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        });
    }

    /** Auth0 のスクリプトが callback に渡すプロフィール形式。 */
    private static Map<String, Object> profile(UserRow user) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("user_id", user.id());
        profile.put("email", user.email());
        profile.put("email_verified", user.emailVerified());
        profile.put("username", user.username());
        profile.put("given_name", user.firstName());
        profile.put("family_name", user.lastName());
        return profile;
    }
}
