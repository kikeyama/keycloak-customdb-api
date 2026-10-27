import NextAuth from "next-auth";
import type { NextRequest } from "next/server";
import { getAuthOptions } from "@/lib/auth";

export const dynamic = "force-dynamic";

type RouteContext = { params: Promise<{ nextauth: string[] }> };

// リクエストごとに環境変数から設定を組み立てる (ビルド時に環境変数を要求しないため)
async function handler(req: NextRequest, context: RouteContext) {
  return NextAuth(req, context, getAuthOptions());
}

export { handler as GET, handler as POST };
