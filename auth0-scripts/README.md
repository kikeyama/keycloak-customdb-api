# auth0-scripts

Auth0 のカスタムデータベース接続(Database Action Scripts)のスクリプト。`api/` の API を呼んで、Keycloak の PostgreSQL に保存されたユーザーとパスワードハッシュで認証する。

## スクリプトと API の対応

| Auth0 のスクリプト | ファイル | 関数 | 呼ぶ API |
|---|---|---|---|
| Login | `login.js` | `login(identifierValue, password, callback)` | `POST /api/login` |
| Get User | `get_user.js` | `getByEmail(identifierValue, callback)` | `GET /api/users?email=` |
| Create | `create.js` | `create(user, callback)` | `POST /api/users` |
| Verify | `verify.js` | `verify(email, callback)` | `POST /api/verify` |
| Change Password | `change_password.js` | `changePassword(email, newPassword, callback)` | `POST /api/change-password` |
| Delete | `delete.js` | `remove(id, callback)` | `DELETE /api/users/{id}` |
| Change Email | `change_email.js` | `changeEmail(email, newEmail, verified, callback)` | `POST /api/change-email` |

シグネチャと戻り値は、Auth0 の公式ドキュメントのテンプレートに合わせてある。`context` 引数は、どのスクリプトにもない。

## 設定

接続設定の **Settings**(Database Action Scripts の下)に、次のキーを登録する。スクリプトは `configuration.<キー>` で読む。

| キー | 内容 |
|---|---|
| `API_BASE_URL` | API の URL(例: `https://keycloak-api.example.com`)。末尾に `/` を付けない |
| `API_KEY` | API の `API_KEY` 環境変数と同じ値(`X-API-Key` ヘッダーで送る) |

**Auth0 から API に到達できる必要がある**。ローカルの `localhost:8080` や、VPC の中だけの URL は使えない。API は EKS にデプロイ済みで、`https://keycloak-customdb-api.a0japan.solutions` で公開している(curl で `/api/login` が成功することは確認済み)。Auth0 の送信元 IP からも届くかは、Auth0 から実際に試して確認する。

## 登録方法

### Login / Create / Verify / Get User / Change Password / Delete

ダッシュボードの **Authentication > Database > (接続) > Custom Database** で、「Use my own database」を有効にし、各スクリプトを貼り付けて保存する。

### Change Email

**ダッシュボードでは設定できない**。Management API(接続の更新で `options.customScripts.change_email`)か、Auth0 Deploy CLI で登録する。

**ダッシュボードでスクリプトを保存すると、登録済みの Change Email が自動的に削除される**。ダッシュボードで他のスクリプトを編集したあとは、Change Email を登録し直すこと。

## 挙動

- **Login**: API が 200 でプロフィールを返せば成功。401 は `WrongUsernameOrPasswordError`。API は、ユーザー不在、無効なユーザー、パスワード不一致を区別せず 401 を返す。その他のステータスや通信エラーは、認証の失敗ではなく `Error`(基盤側の問題)として返す。
- **Get User**: 404 は「ユーザーなし」として `callback(null)`。
- **Create**: 409 は `ValidationError('user_exists', ...)`。`user.user_metadata.given_name` / `family_name` があれば、名前として渡す。
- **Verify**: DB のメールアドレスを確認済みにする。Auth0 側の `email_verified` は変わらない。Login / Get User が返すプロフィールの `email_verified` で反映される。
- **Change Password**: ユーザーが DB にいない(404)場合は `callback(null, false)`。
- **Delete**: 404(既にいない)も成功として扱う。`id` は Auth0 の `user_id` ではなく、**identity の `user_id`**(`auth0|` を除いた部分)。Keycloak のユーザー ID と同じ値。
- **Change Email**: 404(変更元がいない)と 409(新しいメールアドレスが使用済み)は `callback(null, false)`。

返すプロフィールの `user_id` は、Keycloak のユーザー ID(UUID)。Auth0 側では `auth0|<UUID>` になる。

**プロフィールの `username` は、メール形式のときは API が返さない。** Auth0 は、`username` にメールアドレスの形式の値を入れると `"username" cannot be an email address.` で拒否し、ログインが `Wrong email or password` になる(Auth0 のテナントログでは `type: fu`)。API が success を返していても起きる。スクリプトは API のレスポンスをそのまま `callback(null, res.data)` に渡すので、この対応は API 側で行っている。

## 確認の状況

- 7 つのスクリプトが構文として読み込めること(`node --check`)。
- Auth0 の実行環境を模したスタブ(`configuration`、`WrongUsernameOrPasswordError`、`ValidationError`、axios)と、**モックの API サーバー**に対して、成功、失敗、重複、不在、接続失敗の各ケースで期待どおりの `callback` になること。
- **未確認**: 実際の Auth0 テナントでの動作。実際の `api/` に対する呼び出し。Auth0 の実行環境(Node のバージョンや、利用できるモジュール)との差。

## 注意

- **パスワードは平文で API に送る**(Auth0 のスクリプトの仕様)。API の URL は必ず HTTPS にすること。
- スクリプトの中で `console.log` などで、パスワードやトークンを出力しないこと。
- axios のバージョン(`0.22.0`)は、Auth0 のドキュメントのサンプルに合わせた。Auth0 が利用できるバージョンの一覧(`canirequire`)で、利用できることを確認していない。
- API のプロフィールに、パスワードや秘密のキーを含めないこと(Auth0 のテナント管理者から見えるため)。API は含めていない。
