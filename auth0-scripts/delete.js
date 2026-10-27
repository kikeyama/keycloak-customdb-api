/**
 * Delete: Management API またはダッシュボードでユーザーを削除したときに呼ばれ、
 * Keycloak の DB からユーザーを削除する。
 *
 * id は Auth0 の user_id ではなく、**identity の user_id** (先頭の "auth0|" を除いた部分)。
 * Login / Get User が返す profile.user_id (= Keycloak のユーザー ID) と同じ値になる。
 */
function remove(id, callback) {
  const axios = require('axios@0.22.0');

  axios
    .delete(configuration.API_BASE_URL + '/api/users/' + encodeURIComponent(id), {
      headers: { 'X-API-Key': configuration.API_KEY },
      timeout: 10000,
      validateStatus: function () { return true; }
    })
    .then(function (res) {
      // 204: 削除した。404: 既に存在しない (結果は同じなので成功として扱う)。
      if (res.status === 204 || res.status === 404) {
        return callback(null);
      }
      return callback(new Error('delete API returned HTTP ' + res.status));
    })
    .catch(function (err) {
      return callback(new Error('delete API request failed: ' + err.message));
    });
}
