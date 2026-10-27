import { getServerSession } from "next-auth";
import Link from "next/link";
import { redirect } from "next/navigation";
import { LogoutButton } from "@/components/LogoutButton";
import { getAuthOptions } from "@/lib/auth";
import { decodeJwt, formatUnixTime } from "@/lib/jwt";

export const dynamic = "force-dynamic";

function TokenSection({ title, token }: { title: string; token: string | undefined }) {
  const decoded = decodeJwt(token);
  const exp = formatUnixTime(decoded?.payload.exp);
  const iat = formatUnixTime(decoded?.payload.iat);

  return (
    <section>
      <h2>{title}</h2>
      {!token && <p className="muted">トークンがありません。</p>}
      {token && (
        <>
          <h3>Raw</h3>
          <pre>{token}</pre>
          {decoded ? (
            <>
              <p className="muted">
                {iat && <>発行: {iat}　</>}
                {exp && <>有効期限: {exp}</>}
              </p>
              <h3>Header</h3>
              <pre>{JSON.stringify(decoded.header, null, 2)}</pre>
              <h3>Payload (claims)</h3>
              <pre>{JSON.stringify(decoded.payload, null, 2)}</pre>
            </>
          ) : (
            <p className="muted">JWT 形式ではないため (opaque トークンなど)、デコードできません。</p>
          )}
        </>
      )}
    </section>
  );
}

export default async function TokensPage() {
  const session = await getServerSession(getAuthOptions());
  if (!session) redirect("/");

  return (
    <main>
      <h1>Tokens</h1>
      <p className="muted">デコードは表示のためだけで、署名は検証していません。</p>
      <nav>
        <Link href="/home" className="button">
          Home に戻る
        </Link>
        <LogoutButton />
      </nav>
      <TokenSection title="Access Token" token={session.accessToken} />
      <TokenSection title="ID Token" token={session.idToken} />
    </main>
  );
}
