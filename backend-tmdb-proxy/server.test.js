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
