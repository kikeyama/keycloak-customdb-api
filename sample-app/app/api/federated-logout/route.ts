import { getServerSession } from "next-auth";
import { NextResponse } from "next/server";
import { getAuthOptions, getAuthProviderId, required } from "@/lib/auth";

export const dynamic = "force-dynamic";

/**
 * IdP 側のセッションも終了するためのログアウト URL を返す。
 * next-auth の signOut はアプリの Cookie を消すだけで、Keycloak / Auth0 のセッションは残る。
 * 残ったままだと、次のログインでパスワード入力なしにログインされてしまう。
 */
export async function GET() {
  const session = await getServerSession(getAuthOptions());
  const returnTo = required("NEXTAUTH_URL");
  let url: URL;

  if (getAuthProviderId() === "auth0") {
    const issuer = required("AUTH0_ISSUER").replace(/\/+$/, "");
    url = new URL(`${issuer}/v2/logout`);
    url.searchParams.set("client_id", required("AUTH0_CLIENT_ID"));
    url.searchParams.set("returnTo", returnTo);
  } else {
    const issuer = required("KEYCLOAK_ISSUER").replace(/\/+$/, "");
    url = new URL(`${issuer}/protocol/openid-connect/logout`);
    url.searchParams.set("client_id", required("KEYCLOAK_CLIENT_ID"));
    url.searchParams.set("post_logout_redirect_uri", returnTo);
    if (session?.idToken) {
      url.searchParams.set("id_token_hint", session.idToken);
    }
  }

  return NextResponse.json({ url: url.toString() });
}
