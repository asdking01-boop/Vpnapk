const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

async function loadWorkerModule() {
  const code = fs.readFileSync(path.join(__dirname, "worker.js"), "utf8");
  const dataUrl = "data:text/javascript;base64," + Buffer.from(code).toString("base64");
  const mod = await import(dataUrl);
  return mod.default;
}

test("Cloudflare Worker (worker.js) full official TMDb browser authentication, App Link callback, multi-user isolation, sync & metadata", async () => {
  const worker = await loadWorkerModule();
  const env = {
    TMDB_API_KEY: "test_server_secret_tmdb_api_key_v3_998877"
  };

  const originalFetch = global.fetch;
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
          request_token: "official_tmdb_req_tok_abc123"
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    if (p === "/3/authentication/session/new") {
      const body = JSON.parse(options.body || "{}");
      if (body.request_token === "official_tmdb_req_tok_abc123") {
        return new Response(
          JSON.stringify({
            success: true,
            session_id: "real_server_tmdb_session_xyz987"
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
      if (sess === "real_server_tmdb_session_xyz987") {
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

  try {
    // 0. Digital Asset Links (/.well-known/assetlinks.json)
    const assetRes = await worker.fetch(
      new Request("https://kras-tmdb-proxy.asdking01.workers.dev/.well-known/assetlinks.json"),
      env
    );
    assert.equal(assetRes.status, 200);
    const assetJson = await assetRes.json();
    assert.equal(assetJson[0].target.package_name, "com.aistudio.krastv.vmzsqx");

    // 1. Health & /api/tmdb/test
    const testRes = await worker.fetch(
      new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/test"),
      env
    );
    assert.equal(testRes.status, 200);
    const testJson = await testRes.json();
    assert.equal(testJson.ok, true);
    assert.equal(testJson.tmdbConnected, true);
    assert.equal(testJson.accountAuthSupported, true);
    assert.ok(testJson.data.images);

    // 2. Step 1: Request Token creation (POST /api/tmdb/auth/request-token)
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
          state: "nonce_csrf_999"
        })
      }),
      env
    );
    assert.equal(reqTokenRes.status, 200);
    const reqTokenJson = await reqTokenRes.json();
    assert.equal(reqTokenJson.ok, true);
    assert.equal(reqTokenJson.requestToken, "official_tmdb_req_tok_abc123");
    assert.equal(reqTokenJson.state, "nonce_csrf_999");
    assert.ok(
      reqTokenJson.authUrl.startsWith(
        "https://www.themoviedb.org/authenticate/official_tmdb_req_tok_abc123?redirect_to="
      )
    );

    // 3. Step 2: Browser callback after user approves on TMDb (GET /api/tmdb/auth/callback)
    const callbackRes = await worker.fetch(
      new Request(
        "https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/callback?request_token=official_tmdb_req_tok_abc123&approved=true&state=nonce_csrf_999&kras_user=user_profile_1&iid=inst_device_001"
      ),
      env
    );
    assert.equal(callbackRes.status, 200);
    const callbackHtml = await callbackRes.text();
    assert.ok(callbackHtml.includes("Logged in as @kras_movie_fan"));
    assert.ok(!callbackHtml.includes("real_server_tmdb_session_xyz987")); // Raw TMDb session ID must never be in HTML/URL

    const codeMatch = callbackHtml.match(/code=(kras_code_[A-Za-z0-9._-]+)/);
    assert.ok(codeMatch, "Expected one-time authorization code in App Link return URL");
    const oneTimeCode = decodeURIComponent(codeMatch[1]);

    // 4. Step 3: Exchange one-time authorization code for opaque clientToken & profile (POST /api/tmdb/auth/session)
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
          state: "nonce_csrf_999",
          krasUserId: "user_profile_1",
          installationId: "inst_device_001"
        })
      }),
      env
    );
    assert.equal(sessionRes.status, 200);
    const sessionJson = await sessionRes.json();
    assert.equal(sessionJson.ok, true);
    assert.equal(sessionJson.verified, true);
    assert.equal(sessionJson.username, "kras_movie_fan");
    assert.equal(sessionJson.accountId, 445566);
    assert.equal(sessionJson.account.username, "kras_movie_fan");
    assert.ok(sessionJson.clientToken.startsWith("kras_tok_"));
    assert.notEqual(sessionJson.clientToken, "real_server_tmdb_session_xyz987");

    // 5. Replay protection: reusing the same one-time code must fail with HTTP 410
    const replayRes = await worker.fetch(
      new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/session", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          code: oneTimeCode,
          state: "nonce_csrf_999",
          krasUserId: "user_profile_1"
        })
      }),
      env
    );
    assert.equal(replayRes.status, 410);

    // 6. Multi-user isolation: another KRAS TV user profile cannot use user_profile_1's token
    const crossUserSyncRes = await worker.fetch(
      new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/sync", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-TMDb-Session-Id": sessionJson.clientToken,
          "X-KRAS-User-Id": "user_profile_2_intruder"
        },
        body: JSON.stringify({
          accountId: 445566,
          krasUserId: "user_profile_2_intruder"
        })
      }),
      env
    );
    assert.equal(crossUserSyncRes.status, 403);

    // 7. Two-Way Sync for authorized user_profile_1
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
    assert.equal(syncJson.favorites[0].title, "Inception");
    assert.equal(syncJson.watchlist.length, 1);
    assert.equal(syncJson.watchlist[0].title, "Breaking Bad");
    assert.equal(syncJson.ratings.length, 1);
    assert.equal(syncJson.ratings[0].userRating, 9.0);

    // 8. Existing Search & Metadata Enrichment endpoints work
    const searchMovieRes = await worker.fetch(
      new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/search/movie?query=Inception"),
      env
    );
    assert.equal(searchMovieRes.status, 200);
    const searchMovieJson = await searchMovieRes.json();
    assert.equal(searchMovieJson.ok, true);
    assert.equal(searchMovieJson.data.results[0].id, 27205);

    const movieEnrichRes = await worker.fetch(
      new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/movie?title=Inception&year=2010"),
      env
    );
    assert.equal(movieEnrichRes.status, 200);
    const movieEnrichJson = await movieEnrichRes.json();
    assert.equal(movieEnrichJson.ok, true);
    assert.equal(movieEnrichJson.tmdbId, 27205);
    assert.equal(movieEnrichJson.director, "Christopher Nolan");

    // 9. Logout revokes session
    const logoutRes = await worker.fetch(
      new Request("https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/logout", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-TMDb-Session-Id": sessionJson.clientToken
        }
      }),
      env
    );
    assert.equal(logoutRes.status, 200);
    const logoutJson = await logoutRes.json();
    assert.equal(logoutJson.ok, true);
  } finally {
    global.fetch = originalFetch;
  }
});
