"use client";

import { signOut } from "next-auth/react";

export function LogoutButton() {
  return (
    <button
      type="button"
      className="secondary"
      onClick={async () => {
        // セッションを消す前に、IdP のログアウト URL (id_token_hint を含む) を取得しておく
        const res = await fetch("/api/federated-logout");
        const { url } = (await res.json()) as { url: string };
        await signOut({ redirect: false });
        window.location.href = url;
      }}
    >
      Logout
    </button>
  );
}
