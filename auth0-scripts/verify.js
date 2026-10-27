/**
 * Verify: ユーザーが Auth0 の確認メールのリンクを押したときに呼ばれ、
 * Keycloak の DB のメールアドレスを「確認済み」にする。
 *
 * このスクリプトは Auth0 側の email_verified は変更しない。
 * Auth0 側に反映させるには、Login / Get User が返すプロフィールの email_verified を true にする
 * (API は DB の値をそのまま返すので、ここで更新すれば次回から true になる)。
 */
function verify(email, callback) {
  const axios = require('axios@0.22.0');

  axios
    .post(
      configuration.API_BASE_URL + '/api/verify',
      { email: email },
      {
        headers: { 'X-API-Key': configuration.API_KEY },
        timeout: 10000,
        validateStatus: function () { return true; }
      }
    )
    .then(function (res) {
      if (res.status === 200) {
        return callback(null, true);
      }
      // 404 (ユーザー不在) も含め、確認済みにできなかったものはエラーとして返す
      return callback(new Error('verify API returned HTTP ' + res.status));
    })
    .catch(function (err) {
      return callback(new Error('verify API request failed: ' + err.message));
    });
}
