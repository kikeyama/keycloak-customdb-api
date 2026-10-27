/**
 * Login: メールアドレスとパスワードを Keycloak の DB で検証する。
 * Auth0 の「Database Action Scripts」の Login に貼り付ける。
 *
 * 必要な Settings (Database Action Scripts の下の「Settings」):
 *   API_BASE_URL  例: https://keycloak-api.example.com
 *   API_KEY       API の X-API-Key と同じ値
 *
 * 利用できる Node.js モジュール: axios などはバージョンを付けて require する (例: require('axios@0.22.0'))。
 */
function login(identifierValue, password, callback) {
  const axios = require('axios@0.22.0');

  axios
    .post(
      configuration.API_BASE_URL + '/api/login',
      { email: identifierValue, password: password },
      {
        headers: { 'X-API-Key': configuration.API_KEY },
        timeout: 10000,
        // 401 / 404 などを例外にせず、ステータスで判断する
        validateStatus: function () { return true; }
      }
    )
    .then(function (res) {
      if (res.status === 200) {
        // ユーザーが存在してパスワードが一致した。API が返すプロフィールをそのまま渡す。
        return callback(null, res.data);
      }
      if (res.status === 401) {
        // API は「ユーザー不在」「無効なユーザー」「パスワード不一致」を区別せず 401 を返す。
        return callback(new WrongUsernameOrPasswordError(identifierValue, 'invalid credentials'));
      }
      // 400 (入力不正)、5xx など。認証の失敗ではなく、基盤側の問題として扱う。
      return callback(new Error('login API returned HTTP ' + res.status));
    })
    .catch(function (err) {
      // ネットワークエラー、タイムアウトなど
      return callback(new Error('login API request failed: ' + err.message));
    });
}
