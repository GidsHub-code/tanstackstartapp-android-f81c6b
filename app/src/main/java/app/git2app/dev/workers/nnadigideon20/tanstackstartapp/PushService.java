// supabase/functions/send-push/index.ts
//
// KEY FIX: FCM messages now use DATA-ONLY payloads (no "notification" block).
//
// Why this matters for background/killed app notifications:
//   - If you send a "notification" block in the FCM payload, Android FCM SDK
//     intercepts it and shows the notification itself when the app is background/killed.
//     BUT it uses default settings, ignores your channel, and bypasses PushService.
//   - If you send DATA-ONLY (no "notification" block), FCM always calls
//     PushService.onMessageReceived() regardless of app state (foreground/background/killed).
//     Your PushService then builds and shows the notification with the correct channel,
//     sound, vibration, and click behavior — exactly like WhatsApp does.
//
// The second fix: channel_id is now "default_channel" to match strings.xml exactly.

import webpush from "npm:web-push@3.6.7";
import { createClient } from "jsr:@supabase/supabase-js@2";
import { SignJWT, importPKCS8 } from "npm:jose@5.9.6";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const VAPID_PUBLIC = Deno.env.get("VAPID_PUBLIC_KEY") ?? "";
const VAPID_PRIVATE = Deno.env.get("VAPID_PRIVATE_KEY") ?? "";
const FCM_SA_JSON = Deno.env.get("FCM_SERVICE_ACCOUNT_JSON") ?? "";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

if (VAPID_PUBLIC && VAPID_PRIVATE) {
  webpush.setVapidDetails("mailto:support@signalme.app", VAPID_PUBLIC, VAPID_PRIVATE);
}

const admin = createClient(SUPABASE_URL, SERVICE_ROLE, {
  auth: { persistSession: false, autoRefreshToken: false },
});

type Payload = {
  user_id?: string;
  title?: string;
  body?: string;
  url?: string;
  type?: "INSERT" | "UPDATE" | "DELETE";
  table?: string;
  record?: Record<string, any>;
};

type Target = {
  user_id: string;
  title: string;
  body: string;
  url: string;
  notification_id: string;
};

async function resolveTargets(p: Payload): Promise<Target | null> {
  // Direct call with explicit user_id + title
  if (p.user_id && p.title) {
    return {
      user_id: p.user_id,
      title: p.title,
      body: p.body || "",
      url: p.url || "/",
      notification_id: crypto.randomUUID(),
    };
  }

  // Database webhook: notifications table INSERT (signal matches, system alerts)
  if (p.table === "notifications" && p.record) {
    const r = p.record;
    return {
      user_id: r.user_id,
      title: r.title || "SignalMe",
      body: r.body || "",
      url: r.link || "/",
      notification_id: r.id ?? crypto.randomUUID(),
    };
  }

  // Database webhook: messages table INSERT (chat messages)
  if (p.table === "messages" && p.record) {
    const r = p.record;
    const { data: conv } = await admin
      .from("conversations")
      .select("client_id, agent_id")
      .eq("id", r.conversation_id)
      .maybeSingle();
    if (!conv) return null;

    const recipient = r.sender_id === conv.client_id ? conv.agent_id : conv.client_id;
    if (!recipient) return null;

    let preview = r.body as string | null;
    if (!preview) {
      preview =
        r.media_type === "image"
          ? "📷 Photo"
          : r.media_type === "video"
            ? "🎥 Video"
            : r.media_type === "audio"
              ? "🎙️ Voice note"
              : "New message";
    }

    return {
      user_id: recipient,
      title: "New message",
      body: preview!,
      url: `/chats/${r.conversation_id}`,
      notification_id: r.id ?? crypto.randomUUID(),
    };
  }

  return null;
}

// ── FCM HTTP v1 ───────────────────────────────────────────────────────────────

let cachedFcmToken: { token: string; expiresAt: number } | null = null;

async function getFcmAccessToken(): Promise<{ token: string; projectId: string } | null> {
  if (!FCM_SA_JSON) {
    console.error("FCM_SERVICE_ACCOUNT_JSON secret is not set in Supabase edge function");
    return null;
  }
  let sa: any;
  try {
    sa = JSON.parse(FCM_SA_JSON);
  } catch {
    console.error("FCM_SERVICE_ACCOUNT_JSON is not valid JSON");
    return null;
  }
  const projectId = sa.project_id as string;
  if (!projectId) return null;

  const now = Math.floor(Date.now() / 1000);
  if (cachedFcmToken && cachedFcmToken.expiresAt - 60 > now) {
    return { token: cachedFcmToken.token, projectId };
  }

  const key = await importPKCS8(sa.private_key, "RS256");
  const jwt = await new SignJWT({
    scope: "https://www.googleapis.com/auth/firebase.messaging",
  })
    .setProtectedHeader({ alg: "RS256", typ: "JWT", kid: sa.private_key_id })
    .setIssuer(sa.client_email)
    .setAudience("https://oauth2.googleapis.com/token")
    .setIssuedAt(now)
    .setExpirationTime(now + 3600)
    .sign(key);

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });

  if (!res.ok) {
    console.error("FCM OAuth2 token request failed:", res.status, await res.text());
    return null;
  }

  const json = await res.json();
  cachedFcmToken = {
    token: json.access_token,
    expiresAt: now + (json.expires_in ?? 3600),
  };
  return { token: cachedFcmToken.token, projectId };
}

async function sendFcm(
  deviceToken: string,
  t: Target,
): Promise<{ ok: boolean; invalid: boolean }> {
  const auth = await getFcmAccessToken();
  if (!auth) return { ok: false, invalid: false };

  const message = {
    message: {
      token: deviceToken,

      // ── CRITICAL FIX ──────────────────────────────────────────────────────
      // DO NOT include a "notification" block here.
      //
      // With a "notification" block:
      //   • Foreground: PushService.onMessageReceived() is called ✅
      //   • Background: FCM shows notification itself, bypasses PushService ❌
      //   • Killed:     FCM shows notification itself, bypasses PushService ❌
      //
      // With data-only (no "notification" block):
      //   • Foreground: PushService.onMessageReceived() is called ✅
      //   • Background: PushService.onMessageReceived() is called ✅
      //   • Killed:     PushService.onMessageReceived() is called ✅
      //
      // Your PushService already builds and shows the notification correctly
      // with the right channel, sound, vibration, and click action.
      // ──────────────────────────────────────────────────────────────────────

      // Data-only payload — always delivered to PushService.onMessageReceived()
      data: {
        title: t.title,
        body: t.body,
        url: t.url,
        notification_id: t.notification_id,
        // channel must match strings.xml default_notification_channel_id exactly
        channel_id: "default_channel",
      },

      android: {
        // HIGH priority wakes the device even when it's in Doze mode
        priority: "HIGH" as const,
        // ttl: how long FCM retries delivery if device is offline (4 weeks)
        ttl: "2419200s",
      },
    },
  };

  const res = await fetch(
    `https://fcm.googleapis.com/v1/projects/${auth.projectId}/messages:send`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${auth.token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(message),
    },
  );

  if (res.ok) return { ok: true, invalid: false };

  const errBody = await res.text();
  console.error("FCM send failed", res.status, errBody);
  const invalid =
    res.status === 404 || /UNREGISTERED|INVALID_ARGUMENT|NOT_FOUND/.test(errBody);
  return { ok: false, invalid };
}

// ── Web Push ─────────────────────────────────────────────────────────────────

async function sendWebPush(
  s: any,
  t: Target,
): Promise<{ ok: boolean; invalid: boolean }> {
  if (!VAPID_PUBLIC || !VAPID_PRIVATE) return { ok: false, invalid: false };
  try {
    await webpush.sendNotification(
      { endpoint: s.endpoint, keys: { p256dh: s.p256dh, auth: s.auth } },
      JSON.stringify({
        title: t.title,
        body: t.body,
        url: t.url,
        tag: t.notification_id,
      }),
    );
    return { ok: true, invalid: false };
  } catch (err: any) {
    const invalid = err?.statusCode === 404 || err?.statusCode === 410;
    if (!invalid) console.error("web-push error", err?.statusCode, err?.body || err?.message);
    return { ok: false, invalid };
  }
}

// ── Main handler ─────────────────────────────────────────────────────────────

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: corsHeaders });

  try {
    const body = (await req.json()) as Payload;
    const target = await resolveTargets(body);

    if (!target) {
      return new Response(JSON.stringify({ skipped: true }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const { data: subs } = await admin
      .from("push_subscriptions")
      .select("*")
      .eq("user_id", target.user_id);

    if (!subs || subs.length === 0) {
      return new Response(JSON.stringify({ sent: 0, reason: "no subscriptions found" }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    let sent = 0;
    await Promise.all(
      subs.map(async (s: any) => {
        let result: { ok: boolean; invalid: boolean };
        if (s.platform === "android" && s.device_token) {
          result = await sendFcm(s.device_token, target);
        } else {
          result = await sendWebPush(s, target);
        }
        if (result.ok) sent++;
        if (result.invalid) {
          await admin.from("push_subscriptions").delete().eq("id", s.id);
        }
      }),
    );

    return new Response(JSON.stringify({ sent, total: subs.length }), {
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (e: any) {
    console.error(e);
    return new Response(JSON.stringify({ error: e?.message || "unknown error" }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});
