package com.example.kcapi;

import com.example.kcapi.KeycloakUserRepository.UserRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;
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
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        UserRow user = users.findByEmail(request.email())
                .filter(UserRow::enabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials"));
        boolean ok = users.findPasswordCredential(user.id())
                .map(c -> hasher.verify(request.password(), c.credentialData(), c.secretData()))
                .orElse(false);
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        return profile(user);
    }

    /** Verify: メールアドレスを検証済みにする。 */
    @PostMapping("/verify")
    public Map<String, Object> verify(@Valid @RequestBody VerifyRequest request) {
        UserRow user = requireUser(request.email());
        users.markEmailVerified(user.id());
        return profile(requireUser(request.email()));
    }

    /** Create: ユーザーを Keycloak のスキーマで作成する。既存なら 409。 */
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateUserRequest request) {
        if (users.findByEmail(request.email()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "user already exists");
        }
        UserRow user = users.createUser(request.email(), request.givenName(), request.familyName(),
                Boolean.TRUE.equals(request.emailVerified()), hasher.hash(request.password()));
        return profile(user);
    }

    /** Get User: メールアドレスでユーザーを取得する。いなければ 404。 */
    @GetMapping("/users")
    public Map<String, Object> get(@RequestParam String email) {
        return profile(requireUser(email));
    }

    /** Delete: ユーザー ID で削除する。 */
    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        if (!users.deleteUser(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        }
    }

    /** Change Password: パスワードのハッシュを入れ替える。 */
    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        UserRow user = requireUser(request.email());
        users.replacePassword(user.id(), hasher.hash(request.newPassword()));
    }

    /** Change Email: メールアドレス (と、旧メールと同じなら username) を更新する。 */
    @PostMapping("/change-email")
    public Map<String, Object> changeEmail(@Valid @RequestBody ChangeEmailRequest request) {
        UserRow user = requireUser(request.email());
        if (!request.email().equalsIgnoreCase(request.newEmail())
                && users.findByEmail(request.newEmail()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already in use");
        }
        users.changeEmail(user.id(), user.email(), request.newEmail(), Boolean.TRUE.equals(request.emailVerified()));
        return profile(users.findById(user.id()).orElseThrow());
    }

    private UserRow requireUser(String email) {
        return users.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
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
