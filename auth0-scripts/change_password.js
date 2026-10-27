/**
 * Change Password: パスワードリセットの確認リンクを押したあと、新しいパスワードで
 * Keycloak の DB のパスワードハッシュを入れ替える。
 *
 * 第 1 引数は、ユーザーのメールアドレス (「Requires Username」が有効なら username の場合もある)。
 */
function changePassword(email, newPassword, callback) {
  const axios = require('axios@0.22.0');

  axios
    .post(
      configuration.API_BASE_URL + '/api/change-password',
      { email: email, newPassword: newPassword },
      {
        headers: { 'X-API-Key': configuration.API_KEY },
        timeout: 10000,
        validateStatus: function () { return true; }
      }
    )
    .then(function (res) {
      if (res.status === 204) {
        return callback(null, true);
      }
      if (res.status === 404) {
        // ユーザーが DB にいない。更新できなかったことを false で返す。
        return callback(null, false);
      }
      return callback(new Error('change password API returned HTTP ' + res.status));
    })
    .catch(function (err) {
      return callback(new Error('change password API request failed: ' + err.message));
    });
}
