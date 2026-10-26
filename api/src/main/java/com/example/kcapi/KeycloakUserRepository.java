package com.example.kcapi;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Keycloak の user_entity / credential テーブルを直接読み書きする。 */
@Repository
public class KeycloakUserRepository {

    public record UserRow(String id, String username, String email, boolean emailVerified, boolean enabled,
                          String firstName, String lastName, Long createdTimestamp) {
    }

    public record CredentialRow(String credentialData, String secretData) {
    }

    private static final String USER_COLUMNS =
            "id, username, email, email_verified, enabled, first_name, last_name, created_timestamp";

    private final JdbcTemplate jdbc;
    private final String realmName;
    private volatile String realmId;

    public KeycloakUserRepository(JdbcTemplate jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.realmName = props.realmName();
    }

    /** realm_id はレルムの内部 ID (master 以外は UUID)。名前から解決してキャッシュする。 */
    private String realmId() {
        if (realmId == null) {
            realmId = jdbc.queryForObject("select id from realm where name = ?", String.class, realmName);
        }
        return realmId;
    }

    public Optional<UserRow> findByEmail(String email) {
        String normalized = normalize(email);
        return jdbc.query(
                "select " + USER_COLUMNS + " from user_entity where realm_id = ? and (email = ? or username = ?)",
                (rs, i) -> mapUser(rs), realmId(), normalized, normalized).stream().findFirst();
    }

    public Optional<UserRow> findById(String id) {
        return jdbc.query(
                "select " + USER_COLUMNS + " from user_entity where realm_id = ? and id = ?",
                (rs, i) -> mapUser(rs), realmId(), id).stream().findFirst();
    }

    public Optional<CredentialRow> findPasswordCredential(String userId) {
        return jdbc.query(
                "select credential_data, secret_data from credential where user_id = ? and type = 'password' "
                        + "order by priority, created_date desc",
                (rs, i) -> new CredentialRow(rs.getString(1), rs.getString(2)), userId).stream().findFirst();
    }

    @Transactional
    public UserRow createUser(String email, String firstName, String lastName, boolean emailVerified,
                              KeycloakPasswordHasher.Hashed password) {
        String normalized = normalize(email);
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update("insert into user_entity (id, username, email, email_constraint, email_verified, enabled, "
                        + "realm_id, first_name, last_name, created_timestamp, not_before) "
                        + "values (?, ?, ?, ?, ?, true, ?, ?, ?, ?, 0)",
                id, normalized, normalized, normalized, emailVerified, realmId(), firstName, lastName, now);
        insertCredential(id, password, now);
        return findById(id).orElseThrow();
    }

    @Transactional
    public void replacePassword(String userId, KeycloakPasswordHasher.Hashed password) {
        jdbc.update("delete from credential where user_id = ? and type = 'password'", userId);
        insertCredential(userId, password, System.currentTimeMillis());
    }

    @Transactional
    public void markEmailVerified(String userId) {
        jdbc.update("update user_entity set email_verified = true where id = ? and realm_id = ?", userId, realmId());
    }

    /** username が旧メールアドレスと同じ場合は username も追随させる (メールアドレスをユーザー名にしている前提)。 */
    @Transactional
    public void changeEmail(String userId, String oldEmail, String newEmail, boolean verified) {
        String normalized = normalize(newEmail);
        jdbc.update("update user_entity set email = ?, email_constraint = ?, email_verified = ?, "
                        + "username = case when username = ? then ? else username end "
                        + "where id = ? and realm_id = ?",
                normalized, normalized, verified, normalize(oldEmail), normalized, userId, realmId());
    }

    /** 主要な関連テーブルを先に削除する (外部キー制約のため)。全テーブルを網羅してはいない。 */
    @Transactional
    public boolean deleteUser(String userId) {
        for (String table : List.of("credential", "user_attribute", "user_required_action",
                "user_role_mapping", "user_group_membership", "federated_identity")) {
            jdbc.update("delete from " + table + " where user_id = ?", userId);
        }
        return jdbc.update("delete from user_entity where id = ? and realm_id = ?", userId, realmId()) > 0;
    }

    private void insertCredential(String userId, KeycloakPasswordHasher.Hashed password, long now) {
        jdbc.update("insert into credential (id, type, user_id, created_date, secret_data, credential_data, "
                        + "priority, version) values (?, 'password', ?, ?, ?, ?, 10, 0)",
                UUID.randomUUID().toString(), userId, now, password.secretData(), password.credentialData());
    }

    private static UserRow mapUser(java.sql.ResultSet rs) throws java.sql.SQLException {
        long created = rs.getLong("created_timestamp");
        return new UserRow(rs.getString("id"), rs.getString("username"), rs.getString("email"),
                rs.getBoolean("email_verified"), rs.getBoolean("enabled"),
                rs.getString("first_name"), rs.getString("last_name"), rs.wasNull() ? null : created);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase();
    }
}
