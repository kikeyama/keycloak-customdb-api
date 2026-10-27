import type { NextAuthOptions } from "next-auth";
import Auth0Provider from "next-auth/providers/auth0";
import KeycloakProvider from "next-auth/providers/keycloak";

export type AuthProviderId = "keycloak" | "auth0";

export function required(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Missing environment variable: ${name} (see .env.example)`);
  }
  return value;
}

/** 認証の接続先。環境変数 AUTH_PROVIDER で切り替える (既定は keycloak)。 */
export function getAuthProviderId(): AuthProviderId {
  const value = process.env.AUTH_PROVIDER ?? "keycloak";
  if (value !== "keycloak" && value !== "auth0") {
    throw new Error(`AUTH_PROVIDER must be "keycloak" or "auth0" (got "${value}")`);
  }
  return value;
}

export const providerLabels: Record<AuthProviderId, string> = {
  keycloak: "Keycloak",
  auth0: "Auth0",
};

function buildProvider() {
  if (getAuthProviderId() === "auth0") {
    return Auth0Provider({
      clientId: required("AUTH0_CLIENT_ID"),
      clientSecret: required("AUTH0_CLIENT_SECRET"),
      issuer: required("AUTH0_ISSUER"),
    });
  }
  return KeycloakProvider({
    clientId: required("KEYCLOAK_CLIENT_ID"),
    clientSecret: required("KEYCLOAK_CLIENT_SECRET"),
    issuer: required("KEYCLOAK_ISSUER"),
  });
}

/**
 * 環境変数は呼び出し時 (リクエスト時) に読む。モジュール読み込み時に読むと、
 * `next build` の時点で環境変数が必須になり、接続先をコンテナ起動時に切り替えられない。
 */
export function getAuthOptions(): NextAuthOptions {
  return {
    providers: [buildProvider()],
    session: { strategy: "jwt" },
    callbacks: {
      // サインイン直後だけ account が渡される。トークンを JWT (= セッション Cookie) に保存する。
      async jwt({ token, account }) {
        if (account) {
          token.accessToken = account.access_token;
          token.idToken = account.id_token;
          token.accessTokenExpiresAt = account.expires_at;
        }
        return token;
      },
      // トークンの表示がこのアプリの目的なので、セッションにも載せる (本番では載せないこと)。
      async session({ session, token }) {
        session.accessToken = token.accessToken;
        session.idToken = token.idToken;
        session.accessTokenExpiresAt = token.accessTokenExpiresAt;
        session.provider = getAuthProviderId();
        return session;
      },
    },
  };
}
