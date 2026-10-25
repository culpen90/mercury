/** Identity-only enrollment. This service cannot grant access to Google's OAuth application. */
const USERINFO = "https://openidconnect.googleapis.com/v1/userinfo";
const CONSENT_VERSION = "rambler-beta-v1";
const MAX_REQUEST_BYTES = 1_024;
const MAX_USERINFO_BYTES = 16_384;
const MAX_TOKEN_CHARS = 8_192;

type Identity = { subject: string; email: string };

class SafeFailure extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}

function reply(status: number, body?: { status: "enrolled" } | { error: string }): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: {
      "cache-control": "no-store",
      "content-type": "application/json; charset=utf-8",
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer",
    },
  });
}

function bearer(request: Request): string {
  const value = request.headers.get("authorization") ?? "";
  if (value.length > MAX_TOKEN_CHARS + 7) throw new SafeFailure(401, "auth_required");
  const match = /^Bearer ([\x21-\x7e]+)$/i.exec(value);
  if (!match?.[1] || match[1].length > MAX_TOKEN_CHARS) throw new SafeFailure(401, "auth_required");
  return match[1];
}

/** Reads at most the permitted bytes, cancelling oversized and stalled streams. */
async function boundedText(body: ReadableStream<Uint8Array> | null, maximum: number): Promise<string> {
  if (body === null) return "";
  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  let expired = false;
  const timer = setTimeout(() => {
    expired = true;
    void reader.cancel().catch(() => {});
  }, 3_000);
  try {
    while (true) {
      const next = await reader.read();
      if (expired) throw new SafeFailure(400, "invalid_request");
      if (next.done) break;
      length += next.value.byteLength;
      if (length > maximum) throw new SafeFailure(413, "request_too_large");
      chunks.push(next.value);
    }
    const bytes = new Uint8Array(length);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    return new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes);
  } finally {
    clearTimeout(timer);
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

function object(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown> : null;
}

async function payload(request: Request): Promise<Record<string, unknown>> {
  const type = request.headers.get("content-type")?.split(";", 1)[0]?.trim().toLowerCase();
  if (type !== "application/json") throw new SafeFailure(415, "unsupported_media_type");
  const declared = request.headers.get("content-length");
  if (declared !== null && (!/^\d{1,10}$/.test(declared) || Number(declared) > MAX_REQUEST_BYTES)) {
    throw new SafeFailure(413, "request_too_large");
  }
  const text = await boundedText(request.body, MAX_REQUEST_BYTES);
  try {
    const result = object(JSON.parse(text));
    if (result === null) throw new Error();
    return result;
  } catch { throw new SafeFailure(400, "invalid_request"); }
}

/** Google access tokens remain transient and are sent only to this fixed HTTPS endpoint. */
async function verifiedIdentity(token: string): Promise<Identity> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 5_000);
  try {
    const response = await fetch(USERINFO, {
      method: "GET",
      headers: { authorization: `Bearer ${token}`, accept: "application/json" },
      redirect: "manual",
      signal: controller.signal,
    });
    if (response.status === 401 || response.status === 403) {
      await response.body?.cancel();
      throw new SafeFailure(401, "invalid_identity");
    }
    if (!response.ok || response.status !== 200) {
      await response.body?.cancel();
      throw new SafeFailure(503, "auth_unavailable");
    }
    const value = object(JSON.parse(await boundedText(response.body, MAX_USERINFO_BYTES)));
    if (value === null || value.email_verified !== true || typeof value.sub !== "string" ||
      value.sub.length < 1 || value.sub.length > 255 || !/^[\x21-\x7e]+$/.test(value.sub) ||
      typeof value.email !== "string" || value.email.length > 254 ||
      !/^[\x21-\x7e]+$/.test(value.email) || !/^[^@\s]+@[^@\s]+$/.test(value.email)) {
      throw new SafeFailure(401, "invalid_identity");
    }
    return { subject: value.sub, email: value.email.toLowerCase() };
  } catch (error) {
    if (error instanceof SafeFailure && error.status === 401) throw error;
    throw new SafeFailure(503, "auth_unavailable");
  } finally { clearTimeout(timer); }
}

async function signup(db: D1Database, identity: Identity): Promise<Response> {
  const now = Date.now();
  try {
    const results = await db.batch<{ status: string }>([
      db.prepare(`INSERT INTO enrollments(subject,email,consent_version,created_at,updated_at)
        VALUES (?1,?2,?3,?4,?4)
        ON CONFLICT(subject) DO UPDATE SET email=excluded.email, updated_at=excluded.updated_at
        WHERE enrollments.email <> excluded.email`).bind(identity.subject, identity.email, CONSENT_VERSION, now),
      db.prepare("SELECT status FROM enrollments WHERE subject = ?1").bind(identity.subject),
    ]);
    if (results[1]?.results[0]?.status !== "enrolled") throw new SafeFailure(503, "unavailable");
    return reply(200, { status: "enrolled" });
  } catch (error) {
    if (error instanceof Error && error.message.includes("enrollment_capacity_reached")) {
      throw new SafeFailure(503, "capacity_reached");
    }
    throw new SafeFailure(503, "unavailable");
  }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      const url = new URL(request.url);
      if (url.protocol !== "https:" || url.search || url.hash) throw new SafeFailure(400, "invalid_request");
      if (!["/v1/signup", "/v1/status", "/v1/withdraw"].includes(url.pathname)) throw new SafeFailure(404, "not_found");
      const expectedMethod = url.pathname === "/v1/status" ? "GET" : "POST";
      if (request.method !== expectedMethod) throw new SafeFailure(405, "method_not_allowed");
      if (url.pathname !== "/v1/withdraw" && env.GOOGLE_OAUTH_READY !== true) {
        throw new SafeFailure(503, "oauth_not_ready");
      }
      const token = bearer(request);
      if (request.method === "POST") {
        const body = await payload(request);
        const fields = Object.keys(body);
        if (url.pathname === "/v1/signup") {
          if (fields.length !== 1 || fields[0] !== "consentVersion" || typeof body.consentVersion !== "string") {
            throw new SafeFailure(400, "invalid_request");
          }
          if (body.consentVersion !== CONSENT_VERSION) throw new SafeFailure(409, "consent_required");
        } else if (fields.length !== 0) throw new SafeFailure(400, "invalid_request");
      } else if (request.body !== null) throw new SafeFailure(400, "invalid_request");
      const identity = await verifiedIdentity(token);
      if (url.pathname === "/v1/signup") return await signup(env.DB, identity);
      if (url.pathname === "/v1/withdraw") {
        await env.DB.prepare("DELETE FROM enrollments WHERE subject = ?1").bind(identity.subject).run();
        return reply(204);
      }
      const row = await env.DB.prepare("SELECT status FROM enrollments WHERE subject = ?1")
        .bind(identity.subject).first<{ status: string }>();
      if (row === null) throw new SafeFailure(404, "not_registered");
      if (row.status !== "enrolled") throw new SafeFailure(503, "unavailable");
      return reply(200, { status: "enrolled" });
    } catch (error) {
      // Do not log requests, provider replies, exceptions, emails, subjects or tokens.
      return error instanceof SafeFailure ? reply(error.status, { error: error.code }) : reply(503, { error: "unavailable" });
    }
  },
} satisfies ExportedHandler<Env>;
