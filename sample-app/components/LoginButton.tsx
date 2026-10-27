"use client";

import { signIn } from "next-auth/react";

export function LoginButton({ provider, label }: { provider: string; label: string }) {
  return (
    <button type="button" onClick={() => signIn(provider, { callbackUrl: "/home" })}>
      {label}
    </button>
  );
}
