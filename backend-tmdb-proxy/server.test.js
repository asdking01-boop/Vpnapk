const test = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");
const {
  app,
  cleanIptvTitle,
  selectBestTmdbMatch,
  clearCache,
  ipRequestCounters
} = require("./server");

function requestJson(server, path, options = {}) {
  return new Promise((resolve, reject) => {
    const addr = server.address();
    const req = http.request(
      {
        hostname: "127.0.0.1",
        port: addr.port,
        path,
        method: options.method || "GET",
        headers: {
          Accept: "application/json",
          ...(options.body ? { "Content-Type": "application/json" } : {}),
          ...(options.headers || {})
        }
      },
      (res) => {
        let raw = "";
        res.on("data", (chunk) => {
          raw += chunk;
        });
        res.on("end", () => {
          let json = null;
          try {
            json = JSON.parse(raw);
          } catch (_) {
            json = null;
          }
          resolve({ status: res.statusCode, headers: res.headers, body: json, raw });
        });
      }
    );
    req.on("error", reject);
    if (options.body) {
      req.write(JSON.stringify(options.body));
    }
    req.end();
  });
}

test("cleanIptvTitle strips country prefixes, resolution/codec tags, and extracts release years", () => {
  const movieClean = cleanIptvTitle("EN | Dune: Part Two (2024) 4K UHD HEVC 60FPS");
  assert.equal(movieClean.cleanTitle, "Dune: Part Two");
  assert.equal(movieClean.extractedYear, "2024");

  const trailingYear = cleanIptvTitle("AR - The Batman 2022 1080p FHD");
  assert.equal(trailingYear.cleanTitle, "The Batman");
  assert.equal(trailingYear.extractedYear, "2022");

  const seriesClean = cleanIptvTitle("US: Breaking Bad S02E05 [1080p] [MULTI-SUB]");
  assert.equal(seriesClean.cleanTitle, "Breaking Bad");
});

test("selectBestTmdbMatch prioritizes exact title and release year match", () => {
  const candidates = [
    { id: 101, title: "Dune", release_date: "1984-12-14", vote_count: 2500, poster_path: "/a.jpg", overview: "Classic" },
    { id: 438631, title: "Dune", release_date: "2021-09-15", vote_count: 9500, poster_path: "/b.jpg", overview: "Modern" }
  ];
  const best2021 = selectBestTmdbMatch(candidates, "Dune", "2021", false);
  assert.equal(best2021.id, 438631);

  const best1984 = selectBestTmdbMatch(candidates, "Dune", "1984", false);
  assert.equal(best1984.id, 101);
});

test("server endpoints enforce health check, input validation, authentication, and rate limit headers", async () => {
  delete process.env.TMDB_API_KEY;
  delete process.env.PROXY_AUTH_TOKEN;
  clearCache();
  ipRequestCounters.clear();

  const server = http.createServer(app);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));

  try {
    // 1. /health and /api/tmdb/health report 503 unconfigured when TMDB_API_KEY is not set
    const healthRes = await requestJson(server, "/health");
    assert.equal(healthRes.status, 503);
    assert.equal(healthRes.body.ok, false);
    assert.equal(healthRes.body.status, "unconfigured");
    assert.equal(healthRes.body.tmdbConnected, false);
    assert.ok(healthRes.headers["x-ratelimit-limit"]);

    const apiHealthRes = await requestJson(server, "/api/tmdb/health");
    assert.equal(apiHealthRes.status, 503);
    assert.equal(apiHealthRes.body.tmdbConnected, false);

    // 2. Input validation rejects missing or malformed parameters with HTTP 400
    const badSearchMovie = await requestJson(server, "/api/tmdb/search/movie");
    assert.equal(badSearchMovie.status, 400);

    const badYear = await requestJson(server, "/api/tmdb/search/movie?query=Dune&year=abcd");
    assert.equal(badYear.status, 400);

    const badMovieId = await requestJson(server, "/api/tmdb/movie/not_a_number");
    assert.equal(badMovieId.status, 400);

    const badSeason = await requestJson(server, "/api/tmdb/tv/1396/season/999");
    assert.equal(badSeason.status, 400);

    const badMatchType = await requestJson(server, "/api/tmdb/match?title=Dune&type=invalid");
    assert.equal(badMatchType.status, 400);

    // 3. Authentication enforcement when PROXY_AUTH_TOKEN is configured
    process.env.PROXY_AUTH_TOKEN = "secret-kras-token-2026";

    const unauthRes = await requestJson(server, "/api/tmdb/health");
    assert.equal(unauthRes.status, 401);
    assert.equal(unauthRes.body.status, "unauthorized");

    const authHeaderRes = await requestJson(server, "/api/tmdb/health", {
      headers: { "X-KRAS-Proxy-Token": "secret-kras-token-2026" }
    });
    // Passed auth -> reaches health handler (which returns 503 because TMDB_API_KEY is not set)
    assert.equal(authHeaderRes.status, 503);
    assert.equal(authHeaderRes.body.status, "unconfigured");

    const authBearerRes = await requestJson(server, "/api/tmdb/health", {
      headers: { Authorization: "Bearer secret-kras-token-2026" }
    });
    assert.equal(authBearerRes.status, 503);

    const authQueryRes = await requestJson(server, "/api/tmdb/health?token=secret-kras-token-2026");
    assert.equal(authQueryRes.status, 503);
  } finally {
    delete process.env.PROXY_AUTH_TOKEN;
    await new Promise((resolve) => server.close(resolve));
  }
});

test("TMDb account auth & two-way sync endpoints validate inputs and execute full auth + sync workflow", async () => {
  process.env.TMDB_API_KEY = "test_server_side_tmdb_api_key_32chars";
  delete process.env.PROXY_AUTH_TOKEN;
  clearCache();
  ipRequestCounters.clear();

  const originalFetch = global.fetch;
  global.fetch = async (urlStr, options = {}) => {
    const parsed = new URL(urlStr);
    const path = parsed.pathname;

    if (path === "/3/configuration") {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          images: {
            secure_base_url: "https://image.tmdb.org/t/p/"
          }
        })
      };
    }

    if (path === "/3/authentication/token/new") {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          success: true,
          expires_at: "2026-11-06 18:00:00 UTC",
          request_token: "req_token_kras_1234567890"
        })
      };
    }

    if (path === "/3/authentication/session/new") {
      const body = JSON.parse(options.body || "{}");
      if (body.request_token === "req_token_kras_1234567890") {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            success: true,
            session_id: "sess_kras_verified_987654321"
          })
        };
      }
      return {
        ok: false,
        status: 401,
        json: async () => ({ status_code: 17, status_message: "Session denied." })
      };
    }

    if (path === "/3/account") {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          id: 778899,
          username: "kras_cinema_user",
          name: "KRAS Cinema",
          avatar: {
            tmdb: { avatar_path: "/kras_avatar.jpg" },
            gravatar: { hash: "abcdef123456" }
          }
        })
      };
    }

    if (path === "/3/account/778899/favorite" || path === "/3/account/778899/watchlist") {
      return {
        ok: true,
        status: 201,
        json: async () => ({ status_code: 1, status_message: "Success." })
      };
    }

    if (path.endsWith("/rating")) {
      return {
        ok: true,
        status: 201,
        json: async () => ({ status_code: 1, status_message: "Success." })
      };
    }

    if (path === "/3/account/778899/favorite/movies") {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          results: [
            {
              id: 693134,
              title: "Dune: Part Two",
              poster_path: "/dune2.jpg",
              backdrop_path: "/dune2_bg.jpg",
              overview: "Paul Atreides unites with Chani and the Fremen.",
              vote_average: 8.4,
              release_date: "2024-02-27"
            }
          ]
        })
      };
    }

    if (path === "/3/account/778899/watchlist/tv") {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          results: [
            {
              id: 1396,
              name: "Breaking Bad",
              poster_path: "/bb.jpg",
              backdrop_path: "/bb_bg.jpg",
              overview: "A chemistry teacher diagnosed with inoperable lung cancer.",
              vote_average: 8.9,
              first_air_date: "2008-01-20"
            }
          ]
        })
      };
    }

    if (path === "/3/account/778899/rated/movies") {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          results: [
            {
              id: 27205,
              title: "Inception",
              poster_path: "/inception.jpg",
              vote_average: 8.8,
              rating: 9.5,
              release_date: "2010-07-15"
            }
          ]
        })
      };
    }

    return {
      ok: true,
      status: 200,
      json: async () => ({ results: [] })
    };
  };

  const server = http.createServer(app);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));

  try {
    // 0. Existing /api/tmdb/test connectivity endpoint preserved
    const testEndpointRes = await requestJson(server, "/api/tmdb/test");
    assert.equal(testEndpointRes.status, 200);
    assert.equal(testEndpointRes.body.ok, true);

    // 1. Request Token creation
    const tokenRes = await requestJson(server, "/api/tmdb/auth/request-token", {
      method: "POST",
      body: { redirectTo: "krastv://tmdb-auth" }
    });
    assert.equal(tokenRes.status, 200);
    assert.equal(tokenRes.body.ok, true);
    assert.equal(tokenRes.body.requestToken, "req_token_kras_1234567890");
    assert.ok(tokenRes.body.authUrl.includes("https://www.themoviedb.org/authenticate/req_token_kras_1234567890"));

    // 2. Session creation & profile retrieval (verifies both top-level and nested account fields)
    const sessionRes = await requestJson(server, "/api/tmdb/auth/session", {
      method: "POST",
      body: { requestToken: "req_token_kras_1234567890" }
    });
    assert.equal(sessionRes.status, 200);
    assert.equal(sessionRes.body.ok, true);
    assert.equal(sessionRes.body.sessionId, "sess_kras_verified_987654321");
    assert.equal(sessionRes.body.username, "kras_cinema_user");
    assert.equal(sessionRes.body.accountId, 778899);
    assert.equal(sessionRes.body.account.username, "kras_cinema_user");
    assert.equal(sessionRes.body.account.avatarUrl, "https://image.tmdb.org/t/p/w185/kras_avatar.jpg");

    // 3. Two-Way Sync (Favorites, Watchlist, Ratings)
    const syncRes = await requestJson(server, "/api/tmdb/sync", {
      method: "POST",
      headers: { "X-TMDb-Session-Id": "sess_kras_verified_987654321" },
      body: {
        accountId: 778899,
        language: "en-US",
        favorites: [{ tmdbId: 693134, mediaType: "movie", title: "Dune: Part Two", favorite: true }],
        watchlist: [{ tmdbId: 1396, mediaType: "tv", title: "Breaking Bad", watchlist: true }],
        ratings: [{ tmdbId: 27205, mediaType: "movie", title: "Inception", rating: 9.5 }]
      }
    });
    assert.equal(syncRes.status, 200);
    assert.equal(syncRes.body.ok, true);
    assert.equal(syncRes.body.pushedCount, 3);
    assert.equal(syncRes.body.favorites.length, 1);
    assert.equal(syncRes.body.favorites[0].title, "Dune: Part Two");
    assert.equal(syncRes.body.watchlist.length, 1);
    assert.equal(syncRes.body.watchlist[0].title, "Breaking Bad");
    assert.equal(syncRes.body.ratings.length, 1);
    assert.equal(syncRes.body.ratings[0].userRating, 9.5);

    // 4. Logout
    const logoutRes = await requestJson(server, "/api/tmdb/auth/logout", {
      method: "POST",
      headers: { "X-TMDb-Session-Id": "sess_kras_verified_987654321" }
    });
    assert.equal(logoutRes.status, 200);
    assert.equal(logoutRes.body.ok, true);
  } finally {
    global.fetch = originalFetch;
    delete process.env.TMDB_API_KEY;
    await new Promise((resolve) => server.close(resolve));
  }
});

