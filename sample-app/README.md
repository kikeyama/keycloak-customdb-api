# sample-app

Keycloak(または Auth0)でログインして、Access Token と ID Token を確認するための Next.js のサンプルアプリ。認証の接続先は環境変数で切り替える。

## 仕様

| 項目 | 内容 |
|---|---|
| フレームワーク | Next.js 16(App Router)、React 19、TypeScript |
| 認証ライブラリ | next-auth v4(OIDC の Authorization Code Flow) |
| セッション | JWT(Cookie)。サーバー側にセッションを保存しない |
| 接続先 | `AUTH_PROVIDER` で `keycloak` / `auth0` を切り替える |
| Node.js | 20.9 以上(Dockerfile は Node 22) |

### 画面

| パス | 内容 | 認証 |
|---|---|---|
| `/` | トップ画面。ログインボタンを押すと Keycloak / Auth0 のログイン画面に移動する。ログイン済みなら `/home` に移動する | 不要 |
| `/home` | ログイン後のトップ画面。名前とメールアドレスを表示する | 必要 |
| `/tokens` | Access Token と ID Token を表示する。生の JWT と、デコードした header / payload(claims)、発行時刻と有効期限 | 必要 |

未ログインで `/home` や `/tokens` を開くと `/` に移動する。

### API

| パス | 内容 |
|---|---|
| `/api/auth/*` | next-auth のエンドポイント(ログイン、コールバック、セッションなど) |
| `GET /api/federated-logout` | IdP のログアウト URL を返す。Logout ボタンが使う |

### イメージカラー

接続先(`AUTH_PROVIDER`)に合わせて、アプリの色が切り替わる。どちらに繋いでいるか、画面の色で分かる。

| `AUTH_PROVIDER` | 色 | アクセントカラー |
|---|---|---|
| `auth0` | 青(従来の色) | `#1a56db` |
| `keycloak` | 赤 | `#c62828` |

- ボタン、見出しの左の線、接続先のバッジに使われる。
- 仕組み: `app/layout.tsx` が `<body data-provider="...">` を出し、`app/globals.css` の CSS 変数(`--accent` など)を `body[data-provider="..."]` で切り替える。色を変えるときは `globals.css` の変数だけを直す。
- `AUTH_PROVIDER` は起動時の環境変数なので、`layout.tsx` は `dynamic = "force-dynamic"` でリクエストごとに読む(ビルド時に固定すると、同じイメージで接続先を切り替えても色が変わらない)。この指定の副作用で、`/_not-found` も動的になる。

### ログアウト

next-auth の `signOut` はアプリの Cookie を消すだけで、Keycloak / Auth0 側のセッションは残る。残ったままだと、次のログインでパスワードを入力せずにログインされてしまう。そのため Logout ボタンは、アプリのセッションを消したあと、IdP のログアウト URL に移動する。

- Keycloak: `<issuer>/protocol/openid-connect/logout`(`id_token_hint` と `post_logout_redirect_uri` を付ける)
- Auth0: `<issuer>/v2/logout`(`client_id` と `returnTo` を付ける)

### トークンの扱い

- Access Token と ID Token は、デコードして表示するだけで、**署名は検証していない**。
- 表示のために、トークンをセッション Cookie(JWT)に入れている。Cookie が大きくなるうえ、本番のアプリでは避けるべき設計。トークンを確認するためのサンプルとして割り切っている。
- Auth0 は、API の audience を指定しないと、Access Token が JWT ではなく opaque になる。その場合、`/tokens` にはデコードなしの文字列だけが表示される。**環境変数 `AUTH0_AUDIENCE` に API の Identifier を設定すると、その API 向けの JWT の Access Token が発行される**(下記「Auth0 側の設定」)。

### 構成

```
sample-app/
├── app/
│   ├── page.tsx                      # トップ画面
│   ├── home/page.tsx                 # ログイン後のトップ画面
│   ├── tokens/page.tsx               # トークン表示
│   └── api/
│       ├── auth/[...nextauth]/route.ts   # next-auth
│       └── federated-logout/route.ts     # IdP のログアウト URL
├── components/                       # LoginButton / LogoutButton
├── lib/
│   ├── auth.ts                       # 接続先の切り替え、next-auth の設定
│   └── jwt.ts                        # JWT のデコード(表示用)
├── types/next-auth.d.ts              # セッションの型の拡張
├── Dockerfile
└── .env.example
```

環境変数は**リクエスト時**に読む。ビルド時には不要なので、同じイメージを、起動時の環境変数だけで Keycloak にも Auth0 にも向けられる。

## 環境変数

`.env.example` を `.env.local` にコピーして、値を設定する(`.env.local` は git 管理外)。

```bash
cp .env.example .env.local
```

### 共通

| 変数 | 必須 | 内容 |
|---|---|---|
| `AUTH_PROVIDER` | 任意 | `keycloak` または `auth0`。省略時は `keycloak` |
| `NEXTAUTH_URL` | 必須 | このアプリの URL(例: `http://localhost:3000`)。リダイレクトとログアウト後の戻り先に使う |
| `NEXTAUTH_SECRET` | 必須 | セッション Cookie の署名・暗号化に使う。`openssl rand -base64 32` で生成する |

### `AUTH_PROVIDER=keycloak`

| 変数 | 内容 |
|---|---|
| `KEYCLOAK_ISSUER` | **レルムまで含めた** URL。ローカルの Docker は `http://localhost:8081/realms/demo`、EKS は `https://keycloak.<ドメイン>/realms/demo` |
| `KEYCLOAK_CLIENT_ID` | クライアント ID(例: `sample-app`) |
| `KEYCLOAK_CLIENT_SECRET` | クライアントシークレット(Keycloak の Credentials タブ) |

### `AUTH_PROVIDER=auth0`

| 変数 | 内容 |
|---|---|
| `AUTH0_ISSUER` | テナントの URL(例: `https://your-tenant.us.auth0.com`) |
| `AUTH0_CLIENT_ID` | アプリケーションの Client ID |
| `AUTH0_CLIENT_SECRET` | アプリケーションの Client Secret |
| `AUTH0_AUDIENCE` | 任意。Auth0 の API の Identifier(例: `https://sample-app-api`)。**設定すると Access Token が JWT になる**。省略すると opaque になる |

選んでいない接続先の変数は不要。必須の変数が欠けていると、リクエスト時に `Missing environment variable: <名前>` のエラーになる。

### Keycloak 側の設定

管理コンソールで次を作る。

1. レルム(例: `demo`)。`master` は管理用なので、アプリ用には別に作ることをおすすめする。
2. クライアント: クライアント ID は `KEYCLOAK_CLIENT_ID` と同じ値。
   - Client authentication: ON(confidential)
   - Standard flow: ON
   - Valid redirect URIs: `<NEXTAUTH_URL>/api/auth/callback/keycloak`(例: `http://localhost:3000/api/auth/callback/keycloak`)
   - Valid post logout redirect URIs: `<NEXTAUTH_URL>`(例: `http://localhost:3000`)
   - Credentials タブの Client secret を `KEYCLOAK_CLIENT_SECRET` に設定する。
3. ユーザー: メールアドレスとパスワードを持つユーザー。パスワードの **Temporary は OFF** にする(ON だと初回ログイン時にパスワード変更を求められる)。

リダイレクト URI が実際のアプリの URL と一致しないと、Keycloak がエラーを返す。

### Auth0 側の設定

Auth0 のダッシュボードで、Regular Web Application を作る。

- Allowed Callback URLs: `<NEXTAUTH_URL>/api/auth/callback/auth0`
- Allowed Logout URLs: `<NEXTAUTH_URL>`
- Domain、Client ID、Client Secret を、それぞれ `AUTH0_ISSUER`(`https://` 付き)、`AUTH0_CLIENT_ID`、`AUTH0_CLIENT_SECRET` に設定する。

**Access Token を JWT にする(opaque にしない)には**

Access Token が opaque になるのは、認可リクエストに `audience` が付いていないため。次のようにする。

1. Auth0 のダッシュボードの **Applications > APIs** で API を作る。**Identifier**(例: `https://sample-app-api`)が audience になる。この値は、作成後に変更できない。Identifier は URL の形でよいが、実際に到達できる必要はない(Auth0 はその URL を呼ばない)。署名アルゴリズムは RS256 を選ぶ(作成後に変更できない)。
2. `AUTH0_AUDIENCE` に、その Identifier を設定し、dev サーバーを再起動する。
3. ログインし直す(既存のセッションの Access Token は、そのまま opaque)。`/tokens` で JWT として表示される。

- API の **JWT Profile**(`Auth0` / `RFC 9068`)で、Access Token の claims の構造が変わる。
- アプリケーションが、その API へアクセスする権限(Applications > (アプリ) > APIs)を持っている必要がある場合がある。ログインで `access_denied` のようなエラーが出たら、ここを確認する。
- `audience` を指定すると、`/userinfo` 用の opaque な Access Token ではなく、その API 向けのトークンになる。`/userinfo` を呼ぶ用途には、`openid` スコープが必要。
- `audience` の値の指定は、`lib/auth.ts` の `authorization.params` で行う。Auth0 プロバイダーの既定の scope(`openid email profile`)は、マージされるので消えない(next-auth の処理で確認済み)。

## 起動方法

### ローカル(開発)

```bash
cd sample-app
npm install
cp .env.example .env.local   # 値を設定する
npm run dev
```

http://localhost:3000 を開く。

型チェックは `npm run typecheck`。

### ローカル(本番ビルド)

```bash
npm run build
npm run start
```

環境変数は、`.env.local` ではなく、シェルで `export` するか、`.env.production.local` に書く。`next start` は `.env.local` を読むが、`.next/standalone` を直接起動する場合は読まない。

### Docker

イメージのビルドには環境変数は不要。接続先や認証情報は、起動時に渡す。

```bash
docker build -t keycloak-sample-app .

docker run --rm -p 3000:3000 --env-file .env.local keycloak-sample-app
```

- コンテナの中の `localhost` は、コンテナ自身を指す。Mac 上の Docker で動かしている Keycloak に繋ぐときは、`KEYCLOAK_ISSUER` の `localhost` が、**ブラウザからは `localhost:8081`、コンテナからは `host.docker.internal:8081`** と、見え方が異なる。issuer はブラウザのリダイレクトにも使われるため、そのままでは動かない場合がある。EKS 上の Keycloak(公開 URL)に繋ぐなら、この問題は起きない。
- コンテナは非 root ユーザー(`app`)で、ポート 3000 を待ち受ける。
- EKS(amd64)向けにビルドするなら、`docker build --platform linux/amd64 --provenance=false -t <ECR>/keycloak-sample-app:latest .`。

## 動作確認

- 型チェックと、環境変数なしでのビルド、standalone サーバーの起動、`/`(200)、`/api/auth/providers`、未ログインの `/home` のリダイレクトは確認済み。
- **ローカルで Keycloak へのログインを確認済み**: Keycloak のログイン画面に移動し、ログイン後に `/home` が表示され、`/tokens` で Access Token と ID Token が表示される。
- **Auth0 への接続も確認済み**(`AUTH_PROVIDER=auth0`): ログインと、Access Token / ID Token の表示。`AUTH0_AUDIENCE` を指定すると、JWT の Access Token が出る。
- **未確認**: Docker イメージのビルド、ログアウト(IdP のセッション終了)の動作、EKS 上の Keycloak への接続。
