# keycloak-migration

Auth0 のカスタムデータベース接続で、**既存 Keycloak のパスワードを使って Auth0 にもログインできるか**を検証するプロジェクト。

## 構成

| ディレクトリ | 内容 |
|---|---|
| `api/` | Auth0 のカスタムデータベースから呼ぶ API(Java 25 / Spring Boot 4.1.2)。Keycloak の PostgreSQL スキーマを直接読み書きし、Argon2 のパスワードハッシュを検証する |
| `auth0-scripts/` | Auth0 のカスタムデータベースのスクリプト(Login / Get User / Create / Verify / Change Password / Delete / Change Email)。`api/` を呼ぶ。詳細は [`auth0-scripts/README.md`](auth0-scripts/README.md) |
| `sample-app/` | Keycloak(または Auth0)でログインし、Access Token と ID Token を確認する Next.js のサンプルアプリ。接続先は環境変数で切り替える。詳細は [`sample-app/README.md`](sample-app/README.md) |
| `k8s/` | Keycloak の k8s マニフェスト(初期案)。**Terraform 管理に移行したため、参考用に残してあるだけで適用しない** |

Keycloak 本体(EKS + RDS PostgreSQL)は Terraform で管理しており、その Terraform リポジトリは別ディレクトリにある。

## 状況

- Keycloak: EKS にデプロイ済み。
- API: ローカル(Docker の Keycloak / PostgreSQL)で動作確認済み。**EKS にもデプロイ済み**で、`https://keycloak-customdb-api.a0japan.solutions` に対する `POST /api/login` の curl が成功することを確認済み。
- サンプルアプリ(`sample-app/`): ローカルで **Keycloak と Auth0 の両方**で、ログインと Access Token / ID Token の表示まで確認済み。Auth0 は `AUTH0_AUDIENCE` を指定して JWT の Access Token が出ることも確認済み。Docker イメージのビルドは未確認。
- Auth0 側のスクリプト(`auth0-scripts/`): 7 種類を作成。**Auth0 のカスタムデータベース接続での、サインアップとログインは成功することを確認済み**(Login / Create / Get User が実際の Auth0 と API で動いた)。最初は API が success を返すのに `Wrong email or password` になったが、原因は API が返すプロフィールを Auth0 が拒否していたこと: `username` がメールアドレスだった(`"username" cannot be an email address.`)、サインアップで名前が null だった(`Expected type string but found type null`)。どちらも、該当の項目を省くよう API を修正して解消した。Verify / Delete / Change Password / Change Email は未確認。

設計の詳細、調査結果、未確認事項、次のステップは `CLAUDE.md` を参照。

## ローカル環境(Docker)

API の動作確認用に、PostgreSQL と Keycloak をコンテナで起動する。Keycloak が起動時にスキーマを作り、作成したユーザーのパスワードは本物の Argon2 のレコードとして保存される。

```bash
# PostgreSQL (API と Keycloak の両方が同じ DB を見る)
docker run -d --name kc-postgres \
  -e POSTGRES_DB=keycloak -e POSTGRES_USER=keycloak -e POSTGRES_PASSWORD=keycloak \
  -p 5432:5432 \
  postgres:18-alpine

# Keycloak (コンテナからはホスト側の 5432 に host.docker.internal で届く)
docker run -d --name kc-keycloak \
  -e KC_DB=postgres -e KC_DB_URL_HOST=host.docker.internal -e KC_DB_URL_PORT=5432 \
  -e KC_DB_URL_DATABASE=keycloak -e KC_DB_USERNAME=keycloak -e KC_DB_PASSWORD=keycloak \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -p 8081:8080 \
  quay.io/keycloak/keycloak:26.8.1 start-dev
```

- Keycloak の管理コンソール: http://localhost:8081(`admin` / `admin`)
- Keycloak は初回起動に時間がかかる。`docker logs -f kc-keycloak` で `Listening on:` が出るまで待つ。
- Postgres が先に動いている必要がある。接続に失敗して止まったら `docker start kc-keycloak`。
- ローカルの 5432 が使用中なら `-p 5433:5432` にして、`DB_URL` と `KC_DB_URL_PORT` のポートも合わせる。

```bash
docker stop kc-keycloak kc-postgres     # 停止(データは残る)
docker start kc-postgres kc-keycloak    # 再開(Postgres を先に)
docker rm -f kc-keycloak kc-postgres    # 削除(データも消える)
```

### テストユーザーの作成とレコードの確認

```bash
docker exec kc-keycloak bash -c '
  /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user admin --password admin &&
  /opt/keycloak/bin/kcadm.sh create users -r master -s username=test@example.com -s email=test@example.com -s enabled=true &&
  /opt/keycloak/bin/kcadm.sh set-password -r master --username test@example.com --new-password "Test-Passw0rd!"'

docker exec kc-postgres psql -U keycloak -c \
  "select credential_data, secret_data from credential where type='password' order by created_date desc limit 1;"
```

### Keycloak で認証できるか確認する(`grant_type=password`)

管理コンソールではなく、トークンエンドポイントで認証だけを確認する。ロールの割り当てがないユーザー(API が作ったユーザーなど)でも確認できる。

```bash
curl -s -X POST http://localhost:8081/realms/master/protocol/openid-connect/token \
  -d client_id=admin-cli -d grant_type=password \
  -d username=test@example.com -d 'password=Test-Passw0rd!'
```

- `access_token` が返れば認証成功。`{"error":"invalid_grant","error_description":"Invalid user credentials"}` なら、ユーザー名またはパスワードが違う。
- パスワードは**シングルクォート**で囲む。`!` を含む値をダブルクォートで囲むと、zsh / bash がヒストリ展開してしまう。
- `admin-cli` は `master` レルムの組み込みクライアント。`master` 以外のレルムでは、Direct Access Grants を有効にしたクライアントを作り、`client_id`(と必要なら `client_secret`)を変える。URL のレルム名も合わせる。
- API が作ったユーザーには `default-roles-<realm>` がないため、管理コンソールには入れない(`You do not have permission to access this resource`)。認証自体は成功する。

### パスワードハッシュのアルゴリズムを確認する

`credential` テーブルの `credential_data` に、アルゴリズムとイテレーション数が入っている。API はこの値を見て検証方法を決める(`argon2` のみ対応)。

```sql
select u.username,
       c.credential_data::json->>'algorithm'  as algorithm,
       c.credential_data::json->>'hashIterations' as iterations
from credential c join user_entity u on u.id = c.user_id
where c.type = 'password';
```

- 結果が `argon2` / `5` なら Keycloak の既定値。`pbkdf2-sha256` などが混在する場合、API の Login は false を返す(未対応)。
- パラメータ全体を見るには `credential_data` をそのまま取得する(`type` / `version` / `hashLength` / `memory` / `parallelism` が入っている)。
- **`secret_data` はハッシュと salt を含むので、画面に出したり共有したりしない**(テスト用ユーザーを除く)。

**ローカルの Docker の場合**

```bash
docker exec kc-postgres psql -U keycloak -c "<上の SQL>"
```

**RDS の場合**: RDS はプライベートサブネットにあり、SG で許可された Pod からしか届かない。`pod_keycloak_sg` が割り当たる `app=keycloak` ラベルの一時 Pod から `psql` を実行する(`SecurityGroupPolicy` の `podSelector` が `app: keycloak` のため)。

```bash
kubectl -n keycloak run psql-tmp --rm -it --restart=Never \
  --labels=app=keycloak \
  --image=postgres:17 \
  --env=PGHOST=<RDS ホスト名> --env=PGUSER=keycloak --env=PGDATABASE=keycloak \
  -- bash

# Pod の中で(パスワードはシェルの履歴に残さない)
read -s PGPASSWORD; export PGPASSWORD
psql -c "<上の SQL>"
```

- RDS ホスト名: `kubectl -n keycloak get cm keycloak-config -o jsonpath='{.data.KC_DB_URL_HOST}'`、または `terraform output`。
- `--rm` なので、終了すると Pod は削除される。
- `app=keycloak` ラベルは Keycloak の Deployment の `selector` と同じなので、一時 Pod が Service のエンドポイントに入る。ポート 8080 を待ち受けず Ready にならないため、通常のリクエストは届かないが、長時間は残さない。
- Pod が `Pending` のままなら `kubectl -n keycloak describe pod psql-tmp` で原因を確認する。

## API の起動

```bash
cd api
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home   # mvn が JDK 25 を使うように
export MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=KeychainStore -Djavax.net.ssl.trustStore=NONE"  # 証明書エラー(PKIX)が出る環境のみ
export DB_URL=jdbc:postgresql://localhost:5432/keycloak   # jdbc: で始まる必要がある
export DB_USERNAME=keycloak DB_PASSWORD=keycloak
export API_KEY=local-test
mvn spring-boot:run
```

| 環境変数 | 内容 |
|---|---|
| `DB_URL` | `jdbc:postgresql://<ホスト>:5432/keycloak` |
| `DB_USERNAME` / `DB_PASSWORD` | Keycloak の DB ユーザー |
| `API_KEY` | `X-API-Key` ヘッダーの照合に使う共有シークレット |
| `KC_REALM_NAME` | 対象のレルム名(省略時は `master`) |

ポートは 8080。テストは `mvn test`。

## イメージのビルドと EKS へのデプロイ

`api/Dockerfile` は **jar をコピーするだけ**なので、先にホストで jar を作ってから `docker build` する。Docker の中で Maven を動かすと、社内ネットワークの TLS 検査などで Mac のキーチェーンにしかない CA を信頼できず、PKIX の証明書エラーになるため。

```bash
cd api

# 1. jar を作る (JDK 25。mvn --version の Java が 25 であることを確認する)
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=KeychainStore -Djavax.net.ssl.trustStore=NONE"
mvn -DskipTests package

# 2. イメージをビルドする (EKS は amd64)
docker build --platform linux/amd64 --provenance=false \
  -t 222634407479.dkr.ecr.ap-northeast-1.amazonaws.com/keycloak-customdb-api:latest .

# 3. ECR にプッシュする
aws ecr get-login-password --region ap-northeast-1 \
  | docker login --username AWS --password-stdin 222634407479.dkr.ecr.ap-northeast-1.amazonaws.com
docker push 222634407479.dkr.ecr.ap-northeast-1.amazonaws.com/keycloak-customdb-api:latest

# 4. Pod を再作成して新しいイメージにする
kubectl -n keycloak rollout restart deploy/keycloak-customdb-api
kubectl -n keycloak rollout status deploy/keycloak-customdb-api
```

- 手順 1 を飛ばすと、`target/keycloak-auth0-api-*.jar` がなく、`docker build` が `COPY` で失敗する。コードを直したあとは、毎回手順 1 からやり直す(古い jar のままビルドすると、変更が反映されない)。
- タグが `latest` のままだと、Deployment の定義が変わらないため、`rollout restart` をしないと Pod が作り直されない。
- 本番は `https://keycloak-customdb-api.a0japan.solutions`。デプロイ後は `curl -i https://keycloak-customdb-api.a0japan.solutions/actuator/health/readiness` で到達性と DB 接続を確認する。

## 監査ログ

認証の成否と、API キーの拒否を、専用のロガー `audit` で出す。1 行 1 イベントの `key=value` 形式。成功は INFO、失敗と拒否は WARN。

```
event=login result=success username=a@example.com user_id=<Keycloak の ID> client_ip=203.0.113.5
event=login result=failure reason=PASSWORD_MISMATCH username=a@example.com client_ip=203.0.113.5
event=api_key result=rejected reason=API_KEY_MISMATCH method=POST path=/api/login client_ip=203.0.113.5
```

```bash
kubectl -n keycloak logs deploy/keycloak-customdb-api | grep audit        # EKS
kubectl -n keycloak logs deploy/keycloak-customdb-api -f | grep audit     # 流し見
```

| `reason` | 意味 |
|---|---|
| `USER_NOT_FOUND` | ユーザーがいない(メールの綴りや大文字小文字、レルムの違いなど) |
| `USER_DISABLED` | 無効なユーザー |
| `NO_PASSWORD_CREDENTIAL` | パスワードの登録がない |
| `PASSWORD_MISMATCH` | パスワードが一致しない |
| `UNSUPPORTED_ALGORITHM` | ハッシュが argon2 ではない(未対応) |
| `INVALID_RECORD` | 保存されたハッシュのデータが読めない |
| `API_KEY_MISSING` | `X-API-Key` ヘッダーがない |
| `API_KEY_MISMATCH` | `X-API-Key` の値が違う |

- **失敗の理由は、ログにだけ出す**。レスポンスは常に同じ 401。理由をレスポンスに含めると、ユーザーの存在を外部から調べられてしまう。
- パスワードと、受け取った API キーの値は、ログに出さない。
- ユーザー名の改行などの制御文字は `_` に置換し、200 文字に切る(偽のログ行を作られないため)。
- `client_ip` は `X-Forwarded-For` の先頭。ヘッダーは送信側が偽装できるため、監査では参考値。
- ログイン以外(作成、確認、取得、パスワード変更、メール変更、削除)も、成功と、ユーザー不在などの失敗が出る。

### Auth0 の `Wrong email or password` の切り分け

**まず Auth0 のテナントログ(Monitoring > Logs)の `type` と `description` を見る。** API のログが success でも、Auth0 がプロフィールを拒否して失敗することがある。実際にあった例: `type: fu`、`description: "username" cannot be an email address.`(API が返すプロフィールの `username` がメール形式だった。修正済み)。

| ログの状態 | 原因 |
|---|---|
| API は success、Auth0 のテナントログが `fu` / `fs` で `description` にプロフィールの指摘 | API が返すプロフィールを Auth0 が拒否している。例: `"username" cannot be an email address.`(`username` がメール形式)、`Expected type string but found type null on property given_name`(名前が null)。どちらも、該当の項目を省くように修正済み |
| **1 行も出ない** | Auth0 から API に届いていない(Auth0 の Settings の `API_BASE_URL`、Ingress、SG、Auth0 の送信元 IP の許可) |
| `API_KEY_MISMATCH` / `API_KEY_MISSING` | Auth0 の Settings の `API_KEY` が API の `API_KEY` と違う |
| `USER_NOT_FOUND` | Auth0 が渡す identifier が想定と違う |
| `PASSWORD_MISMATCH` | ユーザーはいるが、ハッシュの照合に失敗している |

## エンドポイント

### ヘルスチェック(認証不要)

| 用途 | メソッドとパス | 備考 |
|---|---|---|
| liveness | `GET /actuator/health/liveness` | DB を含まない |
| readiness | `GET /actuator/health/readiness` | DB に接続できるまで失敗する |

### API(`X-API-Key` ヘッダーが必要)

メールアドレスは小文字に正規化される。パスワードは Keycloak と同じ Argon2 で検証・保存する。

| Auth0 のスクリプト | メソッドとパス | リクエスト | 主なレスポンス |
|---|---|---|---|
| Login | `POST /api/login` | JSON: `email`, `password` | 200 プロフィール / 401 認証失敗 |
| Verify | `POST /api/verify` | JSON: `email` | 200 プロフィール / 404 |
| Create | `POST /api/users` | JSON: `email`, `password`, `givenName`, `familyName`, `emailVerified`(後ろ 3 つは任意) | 201 プロフィール / 409 既存 |
| Get User | `GET /api/users?email=` | クエリ: `email` | 200 プロフィール / 404 |
| Delete | `DELETE /api/users/{id}` | パス: Keycloak のユーザー ID | 204 / 404 |
| Change Password | `POST /api/change-password` | JSON: `email`, `newPassword` | 204 / 404 |
| Change Email | `POST /api/change-email` | JSON: `email`, `newEmail`, `emailVerified`(任意) | 200 プロフィール / 404 / 409 重複 |

- `X-API-Key` が無い、または一致しない場合は 401。
- プロフィールの形式: `user_id`, `email`, `email_verified`, `username`, `given_name`, `family_name`。
- Login の失敗は、ユーザー不在・無効なユーザー・パスワード不一致のいずれも同じ 401。

```bash
H='X-API-Key: local-test'
J='Content-Type: application/json'

curl localhost:8080/actuator/health/readiness

curl -X POST localhost:8080/api/login -H "$H" -H "$J" \
  -d '{"email":"test@example.com","password":"Test-Passw0rd!"}'

curl -H "$H" "localhost:8080/api/users?email=test@example.com"

curl -X POST localhost:8080/api/users -H "$H" -H "$J" \
  -d '{"email":"new@example.com","password":"Passw0rd!","givenName":"Taro","familyName":"Yamada","emailVerified":true}'

curl -X POST localhost:8080/api/verify -H "$H" -H "$J" -d '{"email":"new@example.com"}'

curl -X POST localhost:8080/api/change-password -H "$H" -H "$J" \
  -d '{"email":"new@example.com","newPassword":"NewPassw0rd!"}'

curl -X POST localhost:8080/api/change-email -H "$H" -H "$J" \
  -d '{"email":"new@example.com","newEmail":"new2@example.com"}'

curl -X DELETE -H "$H" localhost:8080/api/users/<user_id>
```
