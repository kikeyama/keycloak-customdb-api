import { getServerSession } from "next-auth";
import Link from "next/link";
import { redirect } from "next/navigation";
import { LogoutButton } from "@/components/LogoutButton";
import { getAuthOptions, getAuthProviderId, providerLabels } from "@/lib/auth";

export const dynamic = "force-dynamic";

export default async function HomePage() {
  const session = await getServerSession(getAuthOptions());
  if (!session) redirect("/");

  const label = providerLabels[getAuthProviderId()];

  return (
    <main>
      <h1>Home</h1>
      <p className="muted">
        ログインに成功しました (接続先: <span className="badge">{label}</span>)
      </p>
      <table>
        <tbody>
          <tr>
            <th>Name</th>
            <td>{session.user?.name ?? "-"}</td>
          </tr>
          <tr>
            <th>Email</th>
            <td>{session.user?.email ?? "-"}</td>
          </tr>
        </tbody>
      </table>
      <nav>
        <Link href="/tokens" className="button">
          Access Token / ID Token を表示
        </Link>
        <LogoutButton />
      </nav>
    </main>
  );
}
