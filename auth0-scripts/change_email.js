/**
 * Change Email: Auth0 側でメールアドレスを変更したときに、Keycloak の DB にも反映する。
 *
 * このスクリプトは、ダッシュボードでは設定できない。Management API
 * (PATCH /api/v2/connections/{id} の options.customScripts.change_email) か、
 * Auth0 Deploy CLI で登録する。ダッシュボードでスクリプトを保存すると、登録済みの
 * change_email が自動的に削除されるので注意。
 */
function changeEmail(email, newEmail, verified, callback) {
  const axios = require('axios@0.22.0');

  axios
    .post(
      configuration.API_BASE_URL + '/api/change-email',
      { email: email, newEmail: newEmail, emailVerified: verified === true },
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
      if (res.status === 404 || res.status === 409) {
        // 404: 変更元のユーザーがいない。409: 新しいメールアドレスが既に使われている。
        // どちらも「変更できなかった」ので false で返す。
        return callback(null, false);
      }
      return callback(new Error('change email API returned HTTP ' + res.status));
    })
    .catch(function (err) {
      return callback(new Error('change email API request failed: ' + err.message));
    });
}
