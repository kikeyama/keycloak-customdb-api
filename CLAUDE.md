# keycloak-migration

Keycloak を既存の EKS クラスターにデプロイする(デモ用)。DB は RDS PostgreSQL、namespace は `keycloak`、Pod は 1 個。

## プロジェクトの目的

Auth0 のカスタムデータベース接続を使い、**既存 Keycloak 環境に保存されたパスワードで Auth0 にもログインできるか**を検証する(ユーザー移行/段階的移行の検証)。Keycloak のバックエンドは PostgreSQL。

実現方法:
1. PostgreSQL の前段に API を置く。Auth0 からのリクエストを受け、Postgres に保存された Keycloak のパスワードハッシュで認証する。
2. Auth0 カスタムデータベースのアクションスクリプトを種類ごとに作る(Login / Verify / Create / Get User / Delete / Change Password / Change Email)。

API のエンドポイント(Auth0 のスクリプトに対応): Login, Verify, Create User, Get User, Delete User, Change Password, Change Email。

### ハッシュ互換性の調査結果(Keycloak ソース main ブランチを確認)
- PBKDF2 系(`Pbkdf2PasswordHashProvider`): salt 16 バイト(SecureRandom)、パスワードを PBKDF2 で導出し Base64 で保存。検証は保存された iterations と salt で再計算して値を比較(鍵長は保存値から逆算)。
- `pbkdf2-sha256` のコード上の既定: `PBKDF2WithHmacSHA256`、iterations 600,000、鍵長 256 bit。
- パディング(`max-padding-length`)の既定は 0 = なし(FIPS モード用)。有効な環境では短いパスワードに `\u0000` を足してからハッシュするので、環境の設定を確認すること。
- 標準の PBKDF2 なので Java 以外(Node の `crypto.pbkdf2`、Python の `hashlib.pbkdf2_hmac`、Go など)でも同じハッシュを作れる。Java 必須ではない。
- **実データ(確認済み)**: RDS の `credential` テーブルのパスワードは `"algorithm":"argon2"`、`"hashIterations":5`。確認用 SQL は一時 Pod(`app=keycloak` ラベルで `pod_keycloak_sg` が付く)から `psql` で実行した。`secret_data` はハッシュと salt を含むため出力・共有しない。

### Argon2 の仕様(`crypto/default/.../Argon2PasswordHashProvider.java` 等を確認)
- 実装は BouncyCastle の `Argon2BytesGenerator`。標準の Argon2 (RFC 9106) なので Java 以外でも再現できる(Java 必須ではない)。
- 既定値: type=`id`、version=`1.3`、hashLength=32 バイト、memory=7168 KB、iterations=5、parallelism=1。salt は 16 バイト(SecureRandom)。
- 出力は生のハッシュ(32 バイト)を標準 Base64 でエンコードした文字列。pepper / secret / associated data は使わない。
- 保存先: `credential_data` に `hashIterations`・`algorithm` と、`additionalParameters`(`type` / `version` / `hashLength` / `memory` / `parallelism`)。`secret_data` に `value`(Base64 ハッシュ)と `salt`(Base64、読み込みは MIME デコーダ)。
- 検証は、**レコードに保存されたパラメータ**で再計算して Base64 文字列を比較する。API は固定値ではなくレコードの値を使うこと。
- 設定次第で PBKDF2 など他のアルゴリズムのレコードが混在する可能性があるため、`algorithm` で分岐する。
- **未確認**: パスワードの文字エンコーディング(BouncyCastle の `char[]` 版は UTF-8 変換と想定)。`secret_data` の JSON を Jackson で書き出すときの `salt` のキー名と形式。Java 以外で実装するなら、実レコードでの照合テストで確認する。

### 方針(決定済み)
- 言語は **Java**(Keycloak と同じ BouncyCastle の Argon2 実装で互換性リスクを最小化)。
- Create User / Change Password は **Keycloak の DB に書き込む**(Keycloak と同じ形式で `user_entity` / `credential` を作る)。

## API サービス (`api/`)

Auth0 カスタムデータベースのスクリプトから呼ぶ API。Spring Boot + JDBC(`JdbcTemplate`)で、Keycloak の PostgreSQL スキーマを直接読み書きする。ローカルでの起動、Login、Get User、probe は確認済み。書き込み系(Create / Change Password など)の Keycloak との互換性は未確認(下記「API の未確認事項」参照)。

- 構成: `AuthController`(エンドポイント)、`KeycloakPasswordHasher`(Argon2 の生成・検証)、`KeycloakUserRepository`(SQL)、`ApiKeyFilter`(`/api/**` を `X-API-Key` で保護)、`AppProperties`(設定)。
- 設定(環境変数): `DB_URL`(`jdbc:postgresql://<RDS ホスト>:5432/keycloak`)、`DB_USERNAME`、`DB_PASSWORD`、`API_KEY`、`KC_REALM_NAME`(既定 `master`)。ハッシュのパラメータは `app.hash.*`(既定は Keycloak と同じ)。
- エンドポイント(Auth0 のスクリプトと 1:1):

| スクリプト | エンドポイント | 動作 |
|---|---|---|
| Login | `POST /api/login` | 認証成功でプロフィール、失敗は 401 |
| Verify | `POST /api/verify` | `email_verified` を true にする |
| Create | `POST /api/users` | `user_entity` と `credential` を作成(重複は 409) |
| Get User | `GET /api/users?email=` | プロフィール、いなければ 404 |
| Delete | `DELETE /api/users/{id}` | ユーザーと関連行を削除 |
| Change Password | `POST /api/change-password` | `credential` のハッシュを入れ替える |
| Change Email | `POST /api/change-email` | email(と、旧 email と同じなら username)を更新 |

- 検証は `algorithm = argon2` のレコードのみ対応。それ以外は false。新規作成は Keycloak 既定値(`id`、1.3、7168 KB、5 回、並列度 1、hashLength 32)。
- メールアドレスは小文字に正規化。
- ポートは 8080(管理用ポートは分けない)。Actuator でプローブ用エンドポイントを公開(API キー不要): liveness `/actuator/health/liveness`(DB を含めない)、readiness `/actuator/health/readiness`(`db` を含む = RDS に繋がるまでトラフィックを受けない)。Deployment のプローブは `httpGet` でポート 8080。

### API の未確認事項
- **読み取り側の互換性は確認済み**: Keycloak が作ったレコードで `/api/login` が成功した(Argon2 のパラメータ、salt、Base64 の扱いは一致)。
- **書き込み側の互換性は確認済み**: API の `POST /api/users` で作ったユーザーで、Keycloak のトークンエンドポイントが `access_token` を返した。`credential_data` のキー名(`additionalParameters`)は Keycloak に読まれている(ソースの `PasswordCredentialData` の `@JsonProperty` は `algorithmData` で、なぜ読めるかは未調査)。
- API で作ったユーザーには `user_role_mapping`(`default-roles-<realm>`)がないため、Keycloak の管理コンソールには入れない(「You do not have permission to access this resource」)。ログイン自体は成功する。Auth0 の検証には影響しない。Keycloak 側の画面も使いたい場合は、Create 時にデフォルトロールを割り当てる実装が必要。
- **互換性テスト**: `KeycloakPasswordHasherTest` に、Keycloak が作った実レコードで verify が true になるテストの TODO が残っている(ローカルで手動確認済みだが、自動テスト化していない)。
- **Delete**: 外部キー制約のため主要な関連テーブルのみ先に削除しており、漏れがあると失敗する。
- レルム設定(メールをユーザー名にしているか、メール重複許可か)で挙動が変わる。

### ビルドとバージョン
- Java 25(LTS)/ Spring Boot 4.1.2(Java 17〜27 対応、Jackson 3 = `tools.jackson.*`、スターターは `spring-boot-starter-webmvc`)。`Dockerfile` も Java 25。
- `mvn clean test` は JDK 25 で成功(`KeycloakPasswordHasherTest` 2 件)。ユニットテストは自作ハッシュの往復確認のみ。アプリの起動、DB 接続、Keycloak のレコードでの Login は、ローカルの Docker 環境で確認済み。
- Homebrew の `mvn` は既定で最新の `openjdk`(JDK 27)を使う。JDK 25 で動かすには `JAVA_HOME` を `openjdk@25` に向ける(`mvn --version` で確認)。
- **イメージのビルド**: `Dockerfile` は jar をコピーするだけ。先にホストで `mvn -DskipTests package`(下記の `MAVEN_OPTS` 付き)を実行して `target/*.jar` を作ってから `docker build` する。Docker 内で Maven を動かすと、Mac のキーチェーンにしかない CA を信頼できず PKIX エラーになるため。jar に CA の情報は入らない(実行時の信頼ストアは `eclipse-temurin:25-jre` の `cacerts`)。EKS(amd64)向けは `docker build --platform linux/amd64 --provenance=false -t <ECR>/keycloak-customdb-api:latest .`。
- Maven Central への接続で証明書エラー(PKIX)が出る環境。`MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=KeychainStore -Djavax.net.ssl.trustStore=NONE"` で macOS のキーチェーンを信頼ストアにすると解消する。

## Auth0 側(未着手)
- カスタムデータベースのアクションスクリプト 7 種類(Login / Verify / Create / Get User / Delete / Change Password / Change Email)を作る。上記 API を `X-API-Key` 付きで呼ぶ。
- 想定: Login スクリプトは API の `/api/login` を呼び、成功したプロフィールを callback に返す。

## 現状

- **Keycloak は EKS にデプロイ済みで、アクセスできることを確認済み**(RDS 接続、ALB 経由)。
- `k8s/` の YAML は初期案。**現在は Terraform 管理に移行済み**で、`k8s/` は参考用に残してある(適用しない)。
- Terraform リポジトリは別ディレクトリ(このリポジトリには含まれない)。構成は `env/<region>/main.tf` から `modules/{eks,keycloak,...}` を呼ぶ形(同一ルートモジュール・単一 state)。Keycloak 関連は `modules/keycloak`(RDS、Secrets Manager、SG、ConfigMap、Ingress、Deployment、Service、ESO のリソース)。
- 会話内で作った HCL の一部(Deployment / Service / ExternalSecret 等)は、このリポジトリにファイルとして残していない。
- API(`api/`)はローカル(Docker の Keycloak 26.8.1 / PostgreSQL 18)で動作することをユーザーが確認済み。起動手順、エンドポイント、Docker のコマンドは `README.md` を参照。まだ EKS にはデプロイしていない。
  - **確認済み**: `POST /api/login`(Keycloak が作った Argon2 のレコードで認証成功 = 読み取り側の互換性)、`GET /api/users`、liveness / readiness probe、`POST /api/users`(API が作ったユーザーで Keycloak のトークンエンドポイント `grant_type=password` が成功 = 書き込み側の互換性)。
  - **未確認**: Verify / Delete / Change Password / Change Email の動作。Change Password は Create と同じ `hash()` を使うが、`replacePassword`(削除して挿入)は未検証。

## 次のステップ

1. Keycloak が作った実レコードを使った自動テストを追加する(手動では確認済み)。
2. 残りのエンドポイント(Verify / Delete / Change Password / Change Email)をローカルで確認する。Change Password の後に Keycloak のトークンエンドポイントで新しいパスワードが通ることも確認する。
3. API を EKS にデプロイする設計(未着手): Deployment / Service、Pod 用 SG(RDS の SG に 5432 許可を追加)、ALB 経由の公開。**Auth0 から到達できる必要があるので、インターネットから届く経路と `X-API-Key` による保護が前提**(IP 制限をかけるなら Auth0 の送信元 IP を確認する)。
4. Auth0 のカスタムデータベースのスクリプト 7 種類を作る。
5. 実際の Auth0 テナントで、既存 Keycloak ユーザーのパスワードでログインできるか検証する。

## 設計方針

### Keycloak
- イメージ `quay.io/keycloak/keycloak:26.8.1`、`args: ["start"]`。
- StatefulSet ではなく Deployment を使う。公式が StatefulSet なのは、複数レプリカ時に Infinispan のインメモリデータを失わないようローリング更新を 1 つずつにするため。`replicas: 1` の今回は不要。レプリカを増やすなら StatefulSet + ヘッドレス Service + `KC_CACHE=ispn` + JGroups(7800/57800) が必要。
- ポートは 8080(HTTP)と 9000(管理・ヘルスチェック)。Service は 8080 で統一(80 は使わない)。
- TLS は ALB で終端し、`KC_HTTP_ENABLED=true` / `KC_PROXY_HEADERS=xforwarded`。
- `KC_DB_URL_HOST` はホスト名のみ(ポートは `KC_DB_URL_PORT` に分ける。`:5432` を付けると URL が不正になる)。

### Terraform 管理のリソース
- ConfigMap / Ingress / Deployment / Service はネイティブの `kubernetes_*_v1` リソース。ConfigMap の DB ホストは `aws_db_instance.postgres_keycloak.address` を参照。
- Ingress(ALB): `internet-facing`、HTTPS 443 のみ(80 は開けない)、`subnets` は `var.public_subnet_ids`、`security-groups` は `lb_common_sg` / `home_sg_id` / `vpn_sg_id`、ヘルスチェックは port 9000 の `/health/ready`、`success-codes: "200"`。
- ホスト名は `keycloak.${var.my_domain}`。Route 53 のレコードは ExternalDNS(`policy=sync`, `domainFilters=var.my_domain`)が Ingress の host から自動作成するので Terraform での作成は不要。
- namespace は `kubernetes_namespace.keycloak`(Terraform で定義済み)。

### シークレット
- Secrets Manager のシークレットと値は Terraform 管理(値は tfvars。tfvars と state は git 管理外)。state に平文が残るので保存先の保護が必要。
- JSON のキー名を環境変数名(`KC_DB_USERNAME`, `KC_DB_PASSWORD`, `KC_BOOTSTRAP_ADMIN_USERNAME`, `KC_BOOTSTRAP_ADMIN_PASSWORD`)に揃え、ExternalSecret は `dataFrom.extract` で全キーを取り込む。項目追加時にマッピング編集は不要。
- ESO は `modules/eks` で `helm_release`、SecretStore / ExternalSecret は `modules/keycloak` で `kubernetes_manifest`。認証は EKS Pod Identity(`eso` ロール、namespace `external-secrets`、SA `external-secrets`)。同一アカウントなので SecretStore に `role` / `auth` は不要。
- ConfigMap / Ingress の値(ホスト名、SG、サブネット、ACM ARN)は機密でなく、Ingress は Secret を参照できないため Secrets Manager には入れない。
- 組織の SCP が `secretsmanager:UntagResource` を明示的に拒否する。Secrets Manager に組織の自動化が付ける `sys_*` タグ(`sys_CreatedBy` / `sys_CreatedDate` / `sys_RotatedBy` / `sys_RotatedDate`)を Terraform が削除しようとして失敗するため、ルートの `provider "aws"` で `ignore_tags { key_prefixes = ["sys_"] }` を指定する。`aws_secretsmanager_secret` は `recovery_window_in_days = 0`(削除時に即時削除、復元不可)。

### apply 手順
- `kubernetes_manifest` は plan 時に CRD を要求する。**初回 / 環境作り直し時は** `terraform apply -target=module.eks`(ESO を先に導入)→ 通常の `terraform apply`。サードパーティ provider(kubectl 等)は使わない方針。
- destroy は一括で可能。順序保証のため keycloak モジュールに `depends_on = [module.eks]` を付ける。
- ルートの helm provider にも kubernetes provider と同じ `config_context` を指定する。provider 設定はモジュール内に書かない(`required_providers` の宣言のみ)。

## セキュリティグループ(Pod ごとの SG / VPC CNI)

Pod SG(`pod_keycloak_sg`):
- 受信: ALB の SG(`lb_common_sg_id`)から 8080 と 9000、ノード SG(`eks_nodes_sg_id`)から 9000(kubelet プローブ)。
- 送信: 全許可(絞るなら RDS 5432 と DNS 53 のみ)。
- RDS の SG は `pod_keycloak_sg` からの 5432 を許可。
- ノード SG は `pod_keycloak_sg` からの 53(TCP/UDP)を許可(CoreDNS 向け)。
- ALB の SG の egress が全許可でなければ、Pod SG 宛て 8080/9000 の egress が必要。
- Pod への SG 割り当ては `SecurityGroupPolicy`(作成済み、`podSelector: app=keycloak`)。

## 未確認・要注意

- ESO の CRD の `apiVersion`(`external-secrets.io/v1` か `v1beta1`)はインストール後に `kubectl get crd secretstores.external-secrets.io -o jsonpath='{.spec.versions[*].name}'` で確認。ESO チャートのバージョン候補は 2.13.0(GitHub のリリース情報のみが根拠)で、`helm search repo` で要確認。
- ExternalDNS の helm の `provider` 指定は、チャートの新しいバージョンでは `provider.name`。`version` を固定すること。
- 26.3.0 → 26.8.1 の設定変更は未確認。起動時の警告ログを確認する。
- RDS の `keycloak` ユーザーのパスワードは DB 内で手動設定しており、Secrets Manager の値との一致は手動管理。
- 環境変数で読み込むため、シークレットや ConfigMap を変更しても Pod の再起動までは反映されない。
