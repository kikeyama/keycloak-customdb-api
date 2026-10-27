export type DecodedJwt = {
  header: Record<string, unknown>;
  payload: Record<string, unknown>;
};

/**
 * JWT の header と payload を base64url デコードするだけ。署名は検証しない (表示専用)。
 * JWT 形式でない場合 (Auth0 の opaque なアクセストークンなど) は null を返す。
 */
export function decodeJwt(token: string | undefined): DecodedJwt | null {
  if (!token) return null;
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const parse = (part: string) =>
      JSON.parse(Buffer.from(part, "base64url").toString("utf8")) as Record<string, unknown>;
    return { header: parse(parts[0]), payload: parse(parts[1]) };
  } catch {
    return null;
  }
}

/** exp / iat などの UNIX 秒を読みやすい日時にする。 */
export function formatUnixTime(seconds: unknown): string | null {
  if (typeof seconds !== "number") return null;
  return new Date(seconds * 1000).toLocaleString("ja-JP", { timeZone: "Asia/Tokyo" });
}
