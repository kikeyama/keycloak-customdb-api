import { getServerSession } from "next-auth";
import { redirect } from "next/navigation";
import { LoginButton } from "@/components/LoginButton";
import { getAuthOptions, getAuthProviderId, providerLabels } from "@/lib/auth";

export const dynamic = "force-dynamic";

export default async function TopPage() {
  const session = await getServerSession(getAuthOptions());
  if (session) redirect("/home");

  const provider = getAuthProviderId();
  const label = providerLabels[provider];

  return (
    <main>
      <h1>Sample App</h1>
      <p className="muted">
        認証の接続先: <span className="badge">{label}</span>
      </p>
      <p>ログインボタンを押すと {label} のログイン画面に移動します。</p>
      <LoginButton provider={provider} label={`Login with ${label}`} />
    </main>
  );
}
