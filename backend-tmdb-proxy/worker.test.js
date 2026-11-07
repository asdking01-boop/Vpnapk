const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { DatabaseSync } = require("node:sqlite");

/**
 * Creates an in-memory high-fidelity mock of Cloudflare D1 Database using node:sqlite.
 */
function createMockD1() {
  const sqlite = new DatabaseSync(":memory:");

  function createStatement(query, boundParams = []) {
    return {
      _query: query,
      _params: boundParams,
      bind(...params) {
        return createStatement(query, params);
      },
      async run() {
        const stmt = sqlite.prepare(query);
        const res = stmt.run(...boundParams);
        const changes = Number(res.changes || 0);
        return {
          success: true,
          meta: { changes },
          changes
        };
      },
      async first(col) {
        const stmt = sqlite.prepare(query);
        const row = stmt.get(...boundParams);
        if (!row) return null;
        if (col && typeof col === "string") return row[col];
        return row;
      },
      async all() {
        const stmt = sqlite.prepare(query);
        const rows = stmt.all(...boundParams);
        return {
          success: true,
          results: rows
        };
      }
    };
  }

  return {
    prepare(query) {
      return createStatement(query);
    },
    async batch(statements) {
      const results = [];
      for (const s of statements) {
        results.push(await s.run());
      }
      return results;
    },
    async exec(rawSql) {
      sqlite.exec(rawSql);
    }
  };
}

let cachedWorker = null;
async function loadWorkerModule() {
  if (cachedWorker) return cachedWorker;
  const code = fs.readFileSync(path.join(__dirname, "worker.js"), "utf8");
  const dataUrl = "data:text/javascript;base64," + Buffer.from(code).toString("base64");
  const mod = await import(dataUrl);
  cachedWorker = mod.default;
  return cachedWorker;
}

const originalFetch = global.fetch;

test.before(() => {
  global.fetch = async (urlStr, options = {}) => {
    const parsed = new URL(urlStr);
    const p = parsed.pathname;

    if (p === "/3/configuration") {
      return new Response(
        JSON.stringify({
          images: { secure_base_url: "https://image.tmdb.org/t/p/" },
          change_keys: ["adult", "air_date", "title"]
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    if (p === "/3/authentication/token/new") {
      return new Response(
        JSON.stringify({
          success: true,
          expires_at: "2026-11-06 23:59:59 UTC",
          request_token: "official_tmdb_req_tok_" + Math.random().toString(36).substring(2)
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    if (p === "/3/authentication/session/new") {
      const body = JSON.parse(options.body || "{}");
      if (body.request_token && body.request_token.startsWith("official_tmdb_req_tok_")) {
        return new Response(
          JSON.stringify({
            success: true,
            session_id: "real_server_tmdb_session_" + body.request_token.slice(-6)
          }),
          { status: 200, headers: { "Content-Type": "application/json" } }
        );
      }
      return new Response(
        JSON.stringify({ status_code: 17, status_message: "Session denied" }),
        { status: 401, headers: { "Content-Type": "application/json" } }
      );
    }

    if (p === "/3/account") {
      const sess = parsed.searchParams.get("session_id");
      if (sess && sess.startsWith("real_server_tmdb_session_")) {
        return new Response(
          JSON.stringify({
            id: 445566,
            username: "kras_movie_fan",
            name: "KRAS Movie Fan",
            include_adult: false,
            avatar: {
              tmdb: { avatar_path: "/fan_avatar.jpg" }
            }
          }),
          { status: 200, headers: { "Content-Type": "application/json" } }
        );
      }
      return new Response(JSON.stringify({ status_code: 3 }), { status: 401 });
    }

    if (p === "/3/account/445566/favorite/movies") {
      return new Response(
        JSON.stringify({
          page: 1,
          total_pages: 1,
          results: [
            {
              id: 27205,
              title: "Inception",
              poster_path: "/inception.jpg",
              vote_average: 8.8,
              release_date: "2010-07-15"
            }
          ]
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    if (p === "/3/account/445566/watchlist/tv") {
      return new Response(
        JSON.stringify({
          page: 1,
          total_pages: 1,
          results: [
            {
              id: 1396,
              name: "Breaking Bad",
              poster_path: "/bb.jpg",
              vote_average: 8.9,
              first_air_date: "2008-01-20"
            }
          ]
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    if (p === "/3/account/445566/rated/movies") {
      return new Response(
        JSON.stringify({
          page: 1,
          total_pages: 1,
          results: [
            {
              id: 693134,
              title: "Dune: Part Two",
              poster_path: "/dune2.jpg",
              vote_average: 8.4,
              rating: 9.0,
              release_date: "2024-02-27"
            }
          ]
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    if (
      p === "/3/account/445566/favorite" ||
      p === "/3/account/445566/watchlist" ||
      p.endsWith("/rating")
    ) {
      return new Response(
        JSON.stringify({ status_code: 1, status_message: "Success." }),
        { status: 201, headers: { "Content-Type": "application/json" } }
      );
    }

    if (p === "/3/authentication/session" && options.method === "DELETE") {
      return new Response(JSON.stringify({ success: true }), { status: 200 });
    }

    if (p === "/3/search/movie") {
      return new Response(
        JSON.stringify({
          page: 1,
          results: [
            {
              id: 27205,
              title: "Inception",
              release_date: "2010-07-15",
              poster_path: "/inception.jpg",
              backdrop_path: "/inception_bg.jpg",
              vote_average: 8.8
            }
          ]
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    if (p === "/3/movie/27205") {
      return new Response(
        JSON.stringify({
          id: 27205,
          title: "Inception",
          overview: "A thief who steals corporate secrets through dream-sharing technology.",
          poster_path: "/inception.jpg",
          backdrop_path: "/inception_bg.jpg",
          vote_average: 8.8,
          release_date: "2010-07-15",
          runtime: 148,
          genres: [{ id: 28, name: "Action" }, { id: 878, name: "Sci-Fi" }],
          credits: {
            cast: [{ name: "Leonardo DiCaprio" }, { name: "Joseph Gordon-Levitt" }],
            crew: [{ job: "Director", name: "Christopher Nolan" }]
          }
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    return new Response(JSON.stringify({ page: 1, total_pages: 1, results: [] }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  };
});

test.after(() => {
  global.fetch = originalFetch;
});

test("1. End-to-end official TMDb browser authentication, App Link callback, D1 storage, sync & metadata", async () => {
  const worker = await loadWorkerModule();
  const mockDb = createMockD1();
  const env = {
    TMDB_API_KEY: "test_server_secret_tmdb_api_key_v3_998877",
    SESSION_ENCRYPTION_KEY: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    DB: mockDb
  };

  // Digital Asset Links
  const assetRes = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/.well-known/assetlinks.json"),
    env
  );
  assert.equal(assetRes.status, 200);

  // /api/tmdb/test verifies d1Bound and sessionEncryptionConfigured
  const testRes = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/test"),
    env
  );
  assert.equal(testRes.status, 200);
  const testJson = await testRes.json();
  assert.equal(testJson.ok, true);
  assert.equal(testJson.d1Bound, true);
  assert.equal(testJson.sessionEncryptionConfigured, true);

  // Step 1: Request Token creation
  const reqTokenRes = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/request-token", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-KRAS-User-Id": "user_profile_1",
        "X-KRAS-Installation-Id": "inst_device_001"
      },
      body: JSON.stringify({
        appLinkUrl: "https://kras-tmdb-proxy.asdking01.workers.dev/app/tmdb-auth",
        state: "nonce_csrf_test_001"
      })
    }),
    env
  );
  assert.equal(reqTokenRes.status, 200);
  const reqTokenJson = await reqTokenRes.json();
  assert.equal(reqTokenJson.ok, true);
  assert.ok(reqTokenJson.requestToken);
  assert.equal(reqTokenJson.state, "nonce_csrf_test_001");

  // Step 2: Browser callback
  const callbackRes = await worker.fetch(
    new Request(
      `https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/callback?request_token=${encodeURIComponent(
        reqTokenJson.requestToken
      )}&approved=true&state=nonce_csrf_test_001&kras_user=user_profile_1&iid=inst_device_001`
    ),
    env
  );
  assert.equal(callbackRes.status, 200);
  const callbackHtml = await callbackRes.text();
  const codeMatch = callbackHtml.match(/code=(kras_code_[A-Za-z0-9._-]+)/);
  assert.ok(codeMatch, "Expected one-time auth code in callback HTML");
  const oneTimeCode = decodeURIComponent(codeMatch[1]);

  // Step 3: Session exchange
  const sessionRes = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/session", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-KRAS-User-Id": "user_profile_1",
        "X-KRAS-Installation-Id": "inst_device_001"
      },
      body: JSON.stringify({
        code: oneTimeCode,
        state: "nonce_csrf_test_001",
        krasUserId: "user_profile_1",
        installationId: "inst_device_001"
      })
    }),
    env
  );
  assert.equal(sessionRes.status, 200);
  const sessionJson = await sessionRes.json();
  assert.equal(sessionJson.ok, true);
  assert.equal(sessionJson.username, "kras_movie_fan");
  assert.ok(sessionJson.clientToken.startsWith("kras_tok_"));

  // Account Profile lookup
  const profileRes = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/account", {
      headers: {
        "X-TMDb-Session-Id": sessionJson.clientToken,
        "X-KRAS-User-Id": "user_profile_1"
      }
    }),
    env
  );
  assert.equal(profileRes.status, 200);
  const profileJson = await profileRes.json();
  assert.equal(profileJson.ok, true);
  assert.equal(profileJson.username, "kras_movie_fan");

  // Two-Way Sync
  const syncRes = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/sync", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-TMDb-Session-Id": sessionJson.clientToken,
        "X-KRAS-User-Id": "user_profile_1"
      },
      body: JSON.stringify({
        accountId: 445566,
        krasUserId: "user_profile_1",
        favorites: [],
        watchlist: [],
        ratings: []
      })
    }),
    env
  );
  assert.equal(syncRes.status, 200);
  const syncJson = await syncRes.json();
  assert.equal(syncJson.ok, true);
  assert.equal(syncJson.favorites.length, 1);
  assert.equal(syncJson.watchlist.length, 1);

  // Metadata Lookup
  const metaRes = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/movie?title=Inception&year=2010"),
    env
  );
  assert.equal(metaRes.status, 200);
  const metaJson = await metaRes.json();
  assert.equal(metaJson.ok, true);
  assert.equal(metaJson.director, "Christopher Nolan");
});

test("2. Security: Missing SESSION_ENCRYPTION_KEY or DB returns HTTP 500 configuration errors", async () => {
  const worker = await loadWorkerModule();

  // Missing DB
  const envNoDb = {
    TMDB_API_KEY: "test_key",
    SESSION_ENCRYPTION_KEY: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  };
  const resNoDb = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/request-token", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ state: "xyz123" })
    }),
    envNoDb
  );
  assert.equal(resNoDb.status, 500);
  const jsonNoDb = await resNoDb.json();
  assert.equal(jsonNoDb.ok, false);
  assert.ok(jsonNoDb.error.includes("Cloudflare D1 database (DB) binding is required"));

  // Missing SESSION_ENCRYPTION_KEY
  const envNoKey = {
    TMDB_API_KEY: "test_key",
    DB: createMockD1()
  };
  const resNoKey = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/request-token", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ state: "xyz123" })
    }),
    envNoKey
  );
  assert.equal(resNoKey.status, 500);
  const jsonNoKey = await resNoKey.json();
  assert.equal(jsonNoKey.errorCode, "misconfigured_secret");
});

test("3. Security: Strict state nonce validation (CSRF & missing state prevention)", async () => {
  const worker = await loadWorkerModule();
  const mockDb = createMockD1();
  const env = {
    TMDB_API_KEY: "test_key",
    SESSION_ENCRYPTION_KEY: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    DB: mockDb
  };

  // 1. Missing state in request-token
  const resNoState = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/request-token", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({})
    }),
    env
  );
  assert.equal(resNoState.status, 400);
  const jsonNoState = await resNoState.json();
  assert.equal(jsonNoState.errorCode, "missing_state");

  // Create a legitimate pending request token
  const reqRes = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/request-token", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ state: "correct_state_val" })
    }),
    env
  );
  const reqJson = await reqRes.json();
  const reqToken = reqJson.requestToken;

  // 2. Callback with wrong state -> HTTP 403
  const wrongStateCallback = await worker.fetch(
    new Request(
      `https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/callback?request_token=${encodeURIComponent(
        reqToken
      )}&approved=true&state=attacker_tampered_state`
    ),
    env
  );
  assert.equal(wrongStateCallback.status, 403);

  // 3. Callback with missing state -> HTTP 400
  const missingStateCallback = await worker.fetch(
    new Request(
      `https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/callback?request_token=${encodeURIComponent(
        reqToken
      )}&approved=true`
    ),
    env
  );
  assert.equal(missingStateCallback.status, 400);
});

test("4. Security: Replay attack prevention on one-time authorization code", async () => {
  const worker = await loadWorkerModule();
  const mockDb = createMockD1();
  const env = {
    TMDB_API_KEY: "test_key",
    SESSION_ENCRYPTION_KEY: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    DB: mockDb
  };

  // Create token & authorize
  const reqRes = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/request-token", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-KRAS-User-Id": "user_replay" },
      body: JSON.stringify({ state: "replay_test_state" })
    }),
    env
  );
  const reqToken = (await reqRes.json()).requestToken;

  const cbRes = await worker.fetch(
    new Request(
      `https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/callback?request_token=${encodeURIComponent(
        reqToken
      )}&approved=true&state=replay_test_state&kras_user=user_replay`
    ),
    env
  );
  const callbackHtml = await cbRes.text();
  const codeMatch = callbackHtml.match(/code=(kras_code_[A-Za-z0-9._-]+)/);
  assert.ok(codeMatch, "Expected one-time auth code in callback HTML");
  const code = decodeURIComponent(codeMatch[1]);

  // First exchange: succeeds (HTTP 200)
  const exch1 = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/session", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-KRAS-User-Id": "user_replay" },
      body: JSON.stringify({ code, state: "replay_test_state", krasUserId: "user_replay" })
    }),
    env
  );
  assert.equal(exch1.status, 200);

  // Second exchange: replay attack must fail with HTTP 410
  const exch2 = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/session", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-KRAS-User-Id": "user_replay" },
      body: JSON.stringify({ code, state: "replay_test_state", krasUserId: "user_replay" })
    }),
    env
  );
  assert.equal(exch2.status, 410);
  const exch2Json = await exch2.json();
  assert.equal(exch2Json.errorCode, "code_already_consumed");
});

test("5. Security: Concurrent exchange race condition (atomic single-use enforcement)", async () => {
  const worker = await loadWorkerModule();
  const mockDb = createMockD1();
  const env = {
    TMDB_API_KEY: "test_key",
    SESSION_ENCRYPTION_KEY: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    DB: mockDb
  };

  const reqRes = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/request-token", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-KRAS-User-Id": "user_race" },
      body: JSON.stringify({ state: "race_test_state" })
    }),
    env
  );
  const reqToken = (await reqRes.json()).requestToken;

  const cbRes = await worker.fetch(
    new Request(
      `https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/callback?request_token=${encodeURIComponent(
        reqToken
      )}&approved=true&state=race_test_state&kras_user=user_race`
    ),
    env
  );
  const callbackHtml = await cbRes.text();
  const codeMatch = callbackHtml.match(/code=(kras_code_[A-Za-z0-9._-]+)/);
  assert.ok(codeMatch, "Expected one-time auth code in callback HTML");
  const code = decodeURIComponent(codeMatch[1]);

  // Send two concurrent requests simultaneously with Promise.all
  const [resA, resB] = await Promise.all([
    worker.fetch(
      new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/session", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-KRAS-User-Id": "user_race" },
        body: JSON.stringify({ code, state: "race_test_state", krasUserId: "user_race" })
      }),
      env
    ),
    worker.fetch(
      new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/session", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-KRAS-User-Id": "user_race" },
        body: JSON.stringify({ code, state: "race_test_state", krasUserId: "user_race" })
      }),
      env
    )
  ]);

  const statuses = [resA.status, resB.status].sort();
  assert.deepEqual(statuses, [200, 410], "Exactly one concurrent exchange must succeed (200) and the other fail (410)");
});

test("6. Security: Expired authorization code is rejected with HTTP 410", async () => {
  const worker = await loadWorkerModule();
  const mockDb = createMockD1();
  const env = {
    TMDB_API_KEY: "test_key",
    SESSION_ENCRYPTION_KEY: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    DB: mockDb
  };

  // Ensure schema is created
  await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/health"),
    env
  );

  // Insert an already-expired code into D1
  const expiredCode = "kras_code_expired_test_999";
  const pastMs = Date.now() - 60000;
  await mockDb.exec(`
    CREATE TABLE IF NOT EXISTS tmdb_auth_codes (
      auth_code TEXT PRIMARY KEY,
      client_token TEXT NOT NULL,
      tmdb_session_id TEXT NOT NULL,
      tmdb_account_id INTEGER NOT NULL,
      username TEXT NOT NULL,
      display_name TEXT NOT NULL DEFAULT '',
      avatar_url TEXT NOT NULL DEFAULT '',
      installation_id TEXT NOT NULL DEFAULT '',
      kras_user_id TEXT NOT NULL,
      state_nonce TEXT NOT NULL DEFAULT '',
      consumed INTEGER NOT NULL DEFAULT 0,
      expires_at_ms INTEGER NOT NULL,
      created_at INTEGER NOT NULL
    );
  `);
  await mockDb
    .prepare(
      `INSERT INTO tmdb_auth_codes
       (auth_code, client_token, tmdb_session_id, tmdb_account_id, username, display_name, avatar_url, installation_id, kras_user_id, state_nonce, consumed, expires_at_ms, created_at)
       VALUES (?, 'kras_tok_fake', 'tmdb_sess_fake', 445566, 'fan', 'Fan', '', 'inst1', 'user1', 'expired_state', 0, ?, ?)`
    )
    .bind(expiredCode, pastMs, pastMs - 1000)
    .run();

  const exchRes = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/session", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code: expiredCode, state: "expired_state" })
    }),
    env
  );
  assert.equal(exchRes.status, 410);
  const exchJson = await exchRes.json();
  assert.equal(exchJson.errorCode, "code_expired");
});

test("7. Security: Session revocation on logout and subsequent access rejection", async () => {
  const worker = await loadWorkerModule();
  const mockDb = createMockD1();
  const env = {
    TMDB_API_KEY: "test_key",
    SESSION_ENCRYPTION_KEY: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    DB: mockDb
  };

  // Authenticate user
  const reqRes = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/request-token", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-KRAS-User-Id": "user_logout_test" },
      body: JSON.stringify({ state: "logout_state_001" })
    }),
    env
  );
  const reqToken = (await reqRes.json()).requestToken;

  const cbRes = await worker.fetch(
    new Request(
      `https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/callback?request_token=${encodeURIComponent(
        reqToken
      )}&approved=true&state=logout_state_001&kras_user=user_logout_test`
    ),
    env
  );
  const callbackHtml = await cbRes.text();
  const codeMatch = callbackHtml.match(/code=(kras_code_[A-Za-z0-9._-]+)/);
  assert.ok(codeMatch, "Expected one-time auth code in callback HTML");
  const code = decodeURIComponent(codeMatch[1]);

  const sessRes = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/session", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-KRAS-User-Id": "user_logout_test" },
      body: JSON.stringify({ code, state: "logout_state_001", krasUserId: "user_logout_test" })
    }),
    env
  );
  const clientToken = (await sessRes.json()).clientToken;

  // Verify token works
  const profileBefore = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/account", {
      headers: {
        "X-TMDb-Session-Id": clientToken,
        "X-KRAS-User-Id": "user_logout_test"
      }
    }),
    env
  );
  assert.equal(profileBefore.status, 200);

  // Logout
  const logoutRes = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/logout", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-TMDb-Session-Id": clientToken,
        "X-KRAS-User-Id": "user_logout_test"
      }
    }),
    env
  );
  assert.equal(logoutRes.status, 200);
  const logoutJson = await logoutRes.json();
  assert.equal(logoutJson.ok, true);

  // Verify token is now revoked in D1 and rejected with HTTP 401
  const profileAfter = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/account", {
      headers: {
        "X-TMDb-Session-Id": clientToken,
        "X-KRAS-User-Id": "user_logout_test"
      }
    }),
    env
  );
  assert.equal(profileAfter.status, 401);
  const profileAfterJson = await profileAfter.json();
  assert.equal(profileAfterJson.errorCode, "session_revoked");
});

test("8. Security: Cross-user account access prevention (User B cannot use User A session)", async () => {
  const worker = await loadWorkerModule();
  const mockDb = createMockD1();
  const env = {
    TMDB_API_KEY: "test_key",
    SESSION_ENCRYPTION_KEY: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    DB: mockDb
  };

  // User Alice logs in
  const reqRes = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/request-token", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-KRAS-User-Id": "alice" },
      body: JSON.stringify({ state: "alice_state" })
    }),
    env
  );
  const reqToken = (await reqRes.json()).requestToken;

  const cbRes = await worker.fetch(
    new Request(
      `https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/callback?request_token=${encodeURIComponent(
        reqToken
      )}&approved=true&state=alice_state&kras_user=alice`
    ),
    env
  );
  const callbackHtml = await cbRes.text();
  const codeMatch = callbackHtml.match(/code=(kras_code_[A-Za-z0-9._-]+)/);
  assert.ok(codeMatch, "Expected one-time auth code in callback HTML");
  const code = decodeURIComponent(codeMatch[1]);

  const sessRes = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/session", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-KRAS-User-Id": "alice" },
      body: JSON.stringify({ code, state: "alice_state", krasUserId: "alice" })
    }),
    env
  );
  const aliceToken = (await sessRes.json()).clientToken;

  // Eve tries to access sync or account profile using Alice's token
  const eveSyncRes = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/sync", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-TMDb-Session-Id": aliceToken,
        "X-KRAS-User-Id": "eve_attacker"
      },
      body: JSON.stringify({
        accountId: 445566,
        krasUserId: "eve_attacker"
      })
    }),
    env
  );
  assert.equal(eveSyncRes.status, 403);
  const eveSyncJson = await eveSyncRes.json();
  assert.equal(eveSyncJson.errorCode, "user_mismatch");

  const eveProfileRes = await worker.fetch(
    new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/account", {
      headers: {
        "X-TMDb-Session-Id": aliceToken,
        "X-KRAS-User-Id": "eve_attacker"
      }
    }),
    env
  );
  assert.equal(eveProfileRes.status, 403);
});
