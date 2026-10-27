/**
 * Create: サインアップ (または Management API / ダッシュボードでの作成) で呼ばれ、
 * Keycloak の DB にユーザーを作る。
 *
 * user オブジェクトには email, password, tenant, client_id, connection が入る。
 * 「Requires Username」が有効なら username も入る。
 */
function create(user, callback) {
  const axios = require('axios@0.22.0');

  // user_metadata に given_name / family_name を入れてサインアップした場合に拾う (任意)
  const metadata = user.user_metadata || {};

  axios
    .post(
      configuration.API_BASE_URL + '/api/users',
      {
        email: user.email,
        password: user.password,
        givenName: user.given_name || metadata.given_name,
        familyName: user.family_name || metadata.family_name,
        emailVerified: user.email_verified === true
      },
      {
        headers: { 'X-API-Key': configuration.API_KEY },
        timeout: 10000,
        validateStatus: function () { return true; }
      }
    )
    .then(function (res) {
      if (res.status === 201) {
        return callback(null);
      }
      if (res.status === 409) {
        // 既に同じメールアドレスのユーザーがいる
        return callback(new ValidationError('user_exists', 'the user already exists'));
      }
      return callback(new Error('create API returned HTTP ' + res.status));
    })
    .catch(function (err) {
      return callback(new Error('create API request failed: ' + err.message));
    });
}
