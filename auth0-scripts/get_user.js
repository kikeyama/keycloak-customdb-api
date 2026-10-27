/**
 * Get User: 認証せずに、メールアドレスでユーザーのプロフィールを取得する。
 * パスワードリセット、サインアップ時の重複チェックなどで Auth0 が呼ぶ。
 *
 * 返すプロフィールは Login スクリプトと同じ形式にすること (API が同じ形式を返す)。
 */
function getByEmail(identifierValue, callback) {
  const axios = require('axios@0.22.0');

  axios
    .get(configuration.API_BASE_URL + '/api/users', {
      params: { email: identifierValue },
      headers: { 'X-API-Key': configuration.API_KEY },
      timeout: 10000,
      validateStatus: function () { return true; }
    })
    .then(function (res) {
      if (res.status === 200) {
        return callback(null, res.data);
      }
      if (res.status === 404) {
        // ユーザーが存在しない場合は、エラーではなく「プロフィールなし」で返す。
        return callback(null);
      }
      return callback(new Error('get user API returned HTTP ' + res.status));
    })
    .catch(function (err) {
      return callback(new Error('get user API request failed: ' + err.message));
    });
}
