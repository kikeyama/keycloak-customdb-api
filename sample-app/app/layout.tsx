import type { Metadata } from "next";
import { getAuthProviderId } from "@/lib/auth";
import "./globals.css";

export const metadata: Metadata = {
  title: "Keycloak / Auth0 sample app",
};

// AUTH_PROVIDER は起動時の環境変数なので、ビルド時に固定せず、リクエストごとに読む。
// (固定すると、同じイメージで接続先を切り替えても、色が変わらなくなる)
export const dynamic = "force-dynamic";

export default function RootLayout({ children }: { children: React.ReactNode }) {
  // 接続先ごとにイメージカラーを切り替える (globals.css の body[data-provider="..."])
  return (
    <html lang="ja">
      <body data-provider={getAuthProviderId()}>{children}</body>
    </html>
  );
}
