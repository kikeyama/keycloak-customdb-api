package com.example.kcapi;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 認証の成否などを監査ログとして出す。専用のロガー名 "audit" で出力するので、
 * ログ基盤側でこのロガーだけを抽出できる。1 行 1 イベントの key=value 形式。
 *
 * 記録しないもの: パスワード、受け取った API キーの値。
 * 記録するもの: イベント名、結果、理由、ユーザー名 (メールアドレス)、送信元 IP、パス。
 */
public final class AuditLog {

    private static final Logger audit = LoggerFactory.getLogger("audit");

    /** 認証の失敗理由。API のレスポンスには出さず、ログにだけ出す。 */
    public enum Reason {
        NONE,
        USER_NOT_FOUND,
        USER_DISABLED,
        NO_PASSWORD_CREDENTIAL,
        PASSWORD_MISMATCH,
        UNSUPPORTED_ALGORITHM,
        /** 保存されたハッシュのデータが読めない */
        INVALID_RECORD,
        API_KEY_MISSING,
        API_KEY_MISMATCH
    }

    private AuditLog() {
    }

    public static void loginSuccess(String username, String userId, String clientIp) {
        audit.info("event=login result=success username={} user_id={} client_ip={}",
                clean(username), userId, clientIp);
    }

    public static void loginFailure(String username, Reason reason, String clientIp) {
        audit.warn("event=login result=failure reason={} username={} client_ip={}",
                reason, clean(username), clientIp);
    }

    /** API キーが無い、または一致しないリクエスト。受け取ったキーの値は記録しない。 */
    public static void apiKeyRejected(HttpServletRequest request, Reason reason) {
        audit.warn("event=api_key result=rejected reason={} method={} path={} client_ip={}",
                reason, request.getMethod(), request.getRequestURI(), clientIp(request));
    }

    /** ログイン以外の操作の結果 (作成、削除、パスワード変更など)。 */
    public static void operation(String event, String result, String username, String clientIp) {
        audit.info("event={} result={} username={} client_ip={}", event, result, clean(username), clientIp);
    }

    /**
     * 送信元 IP。ALB の背後にいるので、X-Forwarded-For の先頭が実際のクライアント。
     * このヘッダーは送信側が偽装できるため、監査では参考値として扱う。
     */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return clean(forwarded.split(",")[0].trim());
        }
        return request.getRemoteAddr();
    }

    /**
     * ログインジェクション対策。改行などの制御文字を除き、長さを制限する。
     * ユーザー名は外部から来る値で、改行を含めて偽のログ行を作れてしまうため。
     */
    static String clean(String value) {
        if (value == null) {
            return "-";
        }
        String cleaned = value.replaceAll("[\\p{Cntrl}\\s]+", "_");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) + "..." : cleaned;
    }
}
