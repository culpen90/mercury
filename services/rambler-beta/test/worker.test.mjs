import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { after, before, beforeEach, test } from "node:test";
import { Miniflare, Response, convertV4MiniflareOptions } from "miniflare";

const purpose = "rambler-beta-v1";
const googleUrl = "https://openidconnect.googleapis.com/v1/userinfo";
let instance;
let database;
let upstreamMode = "normal";
let requests = [];

function identity(token) {
  const subject = token.replace("-token", "");
  return { sub: subject, email: `${subject}@example.test`, email_verified: true };
}

async function outbound(request) {
  requests.push({ url: request.url, method: request.method });
  assert.equal(request.url, googleUrl);
  assert.equal(request.method, "GET");
  const token = request.headers.get("authorization")?.replace("Bearer ", "");
  if (upstreamMode === "redirect") return new Response(null, { status: 302, headers: { location: "https://other.test" } });
  if (upstreamMode === "oversized") return Response.json({ ...identity(token), extra: "x".repeat(20_000) });
  if (upstreamMode === "outage") return new Response("private upstream details", { status: 503 });
  if (token === "invalid-token") return new Response("private rejection", { status: 401 });
  if (token === "unverified-token") return Response.json({ ...identity(token), email_verified: false });
  if (token === "missing-sub-token") return Response.json({ email: "person@example.test", email_verified: true });
  return Response.json(identity(token));
}

async function create(ready) {
  return new Miniflare(convertV4MiniflareOptions({
    modules: true,
    scriptPath: "dist/index.js",
    compatibilityDate: "2026-10-19",
    compatibilityFlags: ["nodejs_compat"],
    bindings: { GOOGLE_OAUTH_READY: ready },
    d1Databases: ["DB"],
    outboundService: outbound,
  }));
}

before(async () => {
  instance = await create(true);
  database = await instance.getD1Database("DB");
  const migration = await readFile(new URL("../migrations/0001_enrollments.sql", import.meta.url), "utf8");
  for (const statement of migration.split("-- statement-breakpoint")) await database.prepare(statement).run();
});
after(async () => { await instance?.dispose(); });
beforeEach(async () => {
  await database.prepare("DELETE FROM enrollments").run();
  upstreamMode = "normal";
  requests = [];
});

function call(path, { token = "alice-token", body, method = body === undefined ? "GET" : "POST", headers = {} } = {}) {
  return instance.dispatchFetch(`https://enrollment.test${path}`, {
    method,
    headers: { authorization: `Bearer ${token}`, ...(body === undefined ? {} : { "content-type": "application/json" }), ...headers },
    ...(body === undefined ? {} : { body: typeof body === "string" ? body : JSON.stringify(body) }),
  });
}

test("verified signup automatically enrolls only after production OAuth is ready", async () => {
  const response = await call("/v1/signup", { body: { consentVersion: purpose } });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: "enrolled" });
  assert.equal(requests.length, 1);
  const row = await database.prepare("SELECT * FROM enrollments WHERE subject = 'alice'").first();
  assert.equal(row.email, "alice@example.test");
  assert.equal(row.consent_version, purpose);
  assert.equal(row.status, "enrolled");
  assert.equal(Object.values(row).includes("alice-token"), false);
});

test("default readiness blocks signup and status without contacting Google", async () => {
  const blocked = await create(false);
  try {
    for (const path of ["/v1/signup", "/v1/status"]) {
      const response = await blocked.dispatchFetch(`https://enrollment.test${path}`, {
        method: path.endsWith("signup") ? "POST" : "GET",
        headers: { authorization: "Bearer alice-token", "content-type": "application/json" },
        ...(path.endsWith("signup") ? { body: JSON.stringify({ consentVersion: purpose }) } : {}),
      });
      assert.equal(response.status, 503);
      assert.deepEqual(await response.json(), { error: "oauth_not_ready" });
    }
    assert.equal(requests.length, 0);
  } finally { await blocked.dispose(); }
});

test("repeat signup is idempotent and does not increase the counter", async () => {
  for (let i = 0; i < 3; i++) assert.equal((await call("/v1/signup", { body: { consentVersion: purpose } })).status, 200);
  assert.equal((await database.prepare("SELECT total FROM enrollment_capacity").first()).total, 1);
  assert.equal((await database.prepare("SELECT COUNT(*) AS n FROM enrollments").first()).n, 1);
});

test("status is scoped to the verified subject and never returns an email", async () => {
  await call("/v1/signup", { body: { consentVersion: purpose } });
  const own = await call("/v1/status");
  assert.equal(own.status, 200);
  assert.equal(await own.text(), '{"status":"enrolled"}');
  const other = await call("/v1/status", { token: "bob-token" });
  assert.equal(other.status, 404);
  assert.deepEqual(await other.json(), { error: "not_registered" });
  assert.equal((await call("/v1/status?subject=alice", { token: "bob-token" })).status, 400);
});

test("withdrawal deletes only its verified subject and is idempotent", async () => {
  await call("/v1/signup", { body: { consentVersion: purpose } });
  assert.equal((await call("/v1/withdraw", { token: "bob-token", body: {} })).status, 204);
  assert.equal((await call("/v1/status")).status, 200);
  assert.equal((await call("/v1/withdraw", { body: { subject: "bob" } })).status, 400);
  assert.equal((await call("/v1/withdraw", { body: {} })).status, 204);
  assert.equal((await call("/v1/withdraw", { body: {} })).status, 204);
  assert.equal((await call("/v1/status")).status, 404);
  assert.equal((await database.prepare("SELECT total FROM enrollment_capacity").first()).total, 0);
});

test("withdrawal remains available when signup is disabled", async () => {
  const disabled = await create(false);
  try {
    const db = await disabled.getD1Database("DB");
    const migration = await readFile(new URL("../migrations/0001_enrollments.sql", import.meta.url), "utf8");
    for (const statement of migration.split("-- statement-breakpoint")) await db.prepare(statement).run();
    const response = await disabled.dispatchFetch("https://enrollment.test/v1/withdraw", {
      method: "POST", headers: { authorization: "Bearer alice-token", "content-type": "application/json" }, body: "{}",
    });
    assert.equal(response.status, 204);
  } finally { await disabled.dispose(); }
});

test("missing invalid unverified and malformed Google identities cannot enroll", async () => {
  for (const token of ["invalid-token", "unverified-token", "missing-sub-token"]) {
    const response = await call("/v1/signup", { token, body: { consentVersion: purpose } });
    assert.equal(response.status, 401);
    assert.deepEqual(await response.json(), { error: "invalid_identity" });
  }
  const missing = await call("/v1/signup", { body: { consentVersion: purpose }, headers: { authorization: "" } });
  assert.equal(missing.status, 401);
  assert.equal((await database.prepare("SELECT total FROM enrollment_capacity").first()).total, 0);
});

test("consent must be current and the request cannot supply identity fields", async () => {
  for (const body of [{ consentVersion: purpose, email: "other@example.test" }, [], null, "{", {}]) {
    assert.equal((await call("/v1/signup", { body: typeof body === "string" ? body : JSON.stringify(body) })).status, 400);
  }
  const response = await call("/v1/signup", { body: { consentVersion: "old-purpose" } });
  assert.equal(response.status, 409);
  assert.deepEqual(await response.json(), { error: "consent_required" });
});

test("request bounds are enforced before any Google fetch including streamed bodies", async () => {
  const large = await call("/v1/signup", { body: "x".repeat(1_025) });
  assert.equal(large.status, 413);
  const streamed = new ReadableStream({ start(controller) { controller.enqueue(new Uint8Array(1_025)); controller.close(); } });
  const response = await instance.dispatchFetch("https://enrollment.test/v1/signup", {
    method: "POST", headers: { authorization: "Bearer alice-token", "content-type": "application/json" }, body: streamed, duplex: "half",
  });
  assert.equal(response.status, 413);
  assert.equal(requests.length, 0);
});

test("Google redirects oversized bodies and failures are bounded safe errors", async () => {
  for (const mode of ["redirect", "oversized", "outage"]) {
    requests = [];
    upstreamMode = mode;
    const response = await call("/v1/signup", { body: { consentVersion: purpose } });
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: "auth_unavailable" });
    assert.equal(requests.length, 1);
    assert.equal((await database.prepare("SELECT total FROM enrollment_capacity").first()).total, 0);
  }
});

test("unsupported media methods and admin paths do not create public administration", async () => {
  assert.equal((await call("/v1/signup", { body: "{}", headers: { "content-type": "text/plain" } })).status, 415);
  assert.equal((await call("/v1/signup", { method: "DELETE" })).status, 405);
  assert.equal((await call("/v1/admin", { method: "POST", body: {} })).status, 404);
  assert.equal((await call("/v1/status", { method: "OPTIONS" })).headers.has("access-control-allow-origin"), false);
});

test("responses prohibit caches and contain no authentication or identity values", async () => {
  const response = await call("/v1/signup", { body: { consentVersion: purpose } });
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal(response.headers.get("x-content-type-options"), "nosniff");
  const text = await response.text();
  assert.equal(text.includes("alice"), false);
  assert.equal(text.length < 256, true);
});

test("the atomic counter caps enrollment and concurrency cannot exceed10000", async () => {
  await call("/v1/signup", { body: { consentVersion: purpose } });
  await database.prepare(`WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 9999)
    INSERT INTO enrollments(subject,email,consent_version,created_at,updated_at)
    SELECT 'seed-'||i,'seed-'||i||'@example.test','rambler-beta-v1',1,1 FROM n`).run();
  assert.equal((await database.prepare("SELECT total FROM enrollment_capacity").first()).total, 10_000);
  const capped = await call("/v1/signup", { token: "bob-token", body: { consentVersion: purpose } });
  assert.equal(capped.status, 503);
  assert.deepEqual(await capped.json(), { error: "capacity_reached" });
  assert.equal((await call("/v1/signup", { body: { consentVersion: purpose } })).status, 200);
  await call("/v1/withdraw", { body: {} });
  const simultaneous = await Promise.all(["bob-token", "carol-token"].map(token => call("/v1/signup", { token, body: { consentVersion: purpose } })));
  assert.deepEqual(simultaneous.map(response => response.status).sort(), [200, 503]);
  assert.equal((await database.prepare("SELECT total FROM enrollment_capacity").first()).total, 10_000);
  assert.equal((await database.prepare("SELECT COUNT(*) AS n FROM enrollments").first()).n, 10_000);
});
