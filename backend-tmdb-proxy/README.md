# KRAS TV — Secure TMDb API v3 Backend Proxy (Google Cloud Run)

This directory contains the complete, deployable Node.js backend proxy for **KRAS TV**. It connects to **TMDb API v3** (`https://api.themoviedb.org/3`) using a server-side `TMDB_API_KEY` environment variable injected via **Google Cloud Secret Manager**, ensuring the TMDb API key is **never** exposed in Android code or packaged inside the APK.

---

## 1. Architecture & Security Features

1. **Zero Client Key Exposure**: Reads `TMDB_API_KEY` strictly on the server (`process.env.TMDB_API_KEY` or Secret Manager file mount `process.env.TMDB_API_KEY_FILE`). Supports both TMDb v3 API keys (32-character hex) and TMDb v4 Read Access Bearer tokens.
2. **Authentication (`PROXY_AUTH_TOKEN`)**: Optional shared token verification using constant-time comparison (`crypto.timingSafeEqual`). Accepts `Authorization: Bearer <token>`, `X-KRAS-Proxy-Token: <token>`, or `?token=<token>`.
3. **Sliding-Window IP Rate Limiting**: Configurable per-IP request limits (`RATE_LIMIT_WINDOW_MS`, `RATE_LIMIT_MAX_REQUESTS`) returning standard `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, and `429 Too Many Requests` (`Retry-After`).
4. **CORS & Hardened Headers**: Restricts browser origins via `ALLOWED_ORIGINS` while allowing native Android `OkHttpClient` requests, and sets `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, and `Referrer-Policy: no-referrer`.
5. **Input Validation & IPTV Title Normalization**: Validates all query/path parameters (`title`, `query`, `year`, `tmdbId`, `movieId`, `tvId`, `seasonNumber`, `episodeNumber`, `language`, `page`) and automatically cleans IPTV country prefixes (`EN |`, `AR -`), resolution/codec tags (`4K`, `UHD`, `1080p`, `HEVC`), and `S01E01` markers.
6. **6-Hour Bounded In-Memory Cache**: Caches upstream TMDb responses (up to 3,000 items for 6 hours) so bulk Xtream Codes and M3U playlist imports are fast and respect TMDb rate limits.

---

## 2. API Endpoints Reference

| Endpoint | Method | Description |
| :--- | :--- | :--- |
| `/health` | `GET` | Cloud Run health check & live TMDb API v3 `/3/configuration` connectivity verification. |
| `/api/tmdb/health` | `GET` | Authenticated health & TMDb connectivity check used by KRAS TV Settings **Save & Test**. |
| `/api/tmdb/connectivity-test` | `GET` | Alias for `/api/tmdb/health` verifying `TMDB_API_KEY` against TMDb API v3. |
| `/api/tmdb/search/movie` | `GET` | Movie search (`?query=<title>&year=<year>&language=en-US&page=1`). |
| `/api/tmdb/search/tv` | `GET` | TV series search (`?query=<title>&year=<year>&language=en-US&page=1`). |
| `/api/tmdb/movie/:movieId` | `GET` | Movie details, posters, backdrops, ratings, genres, cast, and director by TMDb ID. |
| `/api/tmdb/tv/:tvId` | `GET` | TV series details, posters, backdrops, ratings, genres, cast, creators, and seasons list by TMDb ID. |
| `/api/tmdb/tv/:tvId/season/:seasonNumber` | `GET` | Season details and full episodes list (titles, overviews, still images, ratings, durations, air dates). |
| `/api/tmdb/tv/:tvId/season/:seasonNumber/episode/:episodeNumber` | `GET` | Single episode details, guest stars, and director. |
| `/api/tmdb/match` | `GET` / `POST` | Smart IPTV title matching (`?title=<raw_iptv_name>&type=movie\|tv&year=<year>&tmdbId=<id>&language=en-US`). |
| `/api/tmdb/movie` | `GET` | Unified movie match + details (`?title=<raw_name>&year=<year>&tmdbId=<id>&language=en-US`). |
| `/api/tmdb/tv` | `GET` | Unified TV series match + details + seasons (`?title=<raw_name>&year=<year>&tmdbId=<id>&language=en-US`). |

---

## 3. Configuring `TMDB_API_KEY` with Google Cloud Secret Manager & Deploying to Cloud Run

### Step 1: Set Your Google Cloud Project & Enable APIs
```bash
export GCP_PROJECT_ID="your-gcp-project-id"
export GCP_REGION="europe-west3"

gcloud config set project "${GCP_PROJECT_ID}"
gcloud services enable \
  run.googleapis.com \
  secretmanager.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com
```

### Step 2: Store `TMDB_API_KEY` Securely in Google Cloud Secret Manager
Create the secret in Secret Manager so your TMDb API key is encrypted at rest and never stored in source control or container images:
```bash
printf "YOUR_REAL_TMDB_V3_API_KEY" | gcloud secrets create TMDB_API_KEY \
  --replication-policy="automatic" \
  --data-file=-
```
*(If the secret already exists and you want to rotate or update the key, run:)*
```bash
printf "YOUR_REAL_TMDB_V3_API_KEY" | gcloud secrets versions add TMDB_API_KEY --data-file=-
```

### Step 3: Grant Cloud Run Access to the Secret
Allow the Cloud Run runtime service account to read `TMDB_API_KEY` from Secret Manager:
```bash
PROJECT_NUMBER="$(gcloud projects describe "${GCP_PROJECT_ID}" --format='value(projectNumber)')"
gcloud secrets add-iam-policy-binding TMDB_API_KEY \
  --member="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"
```

### Step 4: Deploy to Google Cloud Run
Run the deployment from `/backend-tmdb-proxy` (or execute `./deploy-cloud-run.sh`):
```bash
cd backend-tmdb-proxy

gcloud run deploy kras-tmdb-proxy \
  --source . \
  --region "${GCP_REGION}" \
  --platform managed \
  --allow-unauthenticated \
  --port 8080 \
  --memory 256Mi \
  --cpu 1 \
  --max-instances 10 \
  --set-secrets="TMDB_API_KEY=TMDB_API_KEY:latest"
```

### Step 5: Verify Live TMDb Connectivity
Once Cloud Run outputs your service URL (for example, `https://kras-tmdb-proxy-xxxx.europe-west3.run.app`), test the health endpoint:
```bash
curl -s https://kras-tmdb-proxy-xxxx.europe-west3.run.app/api/tmdb/health
```
Expected response when `TMDB_API_KEY` is valid:
```json
{
  "ok": true,
  "status": "connected",
  "service": "kras-tv-tmdb-proxy",
  "tmdbApiVersion": "v3",
  "tmdbConnected": true,
  "imageBaseUrl": "https://image.tmdb.org/t/p/",
  "authRequired": false,
  "cacheEntries": 0,
  "message": "Connected to TMDb API v3 via secure server-side proxy."
}
```

---

## 4. Connecting KRAS TV to Your Deployed Backend URL

1. Open **KRAS TV** on your Android device and go to **Settings → TMDb Metadata Proxy**.
2. Paste your deployed Cloud Run URL into the **Backend Proxy Base URL** field:
   - Standard URL: `https://kras-tmdb-proxy-xxxx.europe-west3.run.app`
   - If you also configured `PROXY_AUTH_TOKEN` on the server, you can append `?token=YOUR_PROXY_AUTH_TOKEN` directly to the URL in Settings:
     `https://kras-tmdb-proxy-xxxx.europe-west3.run.app?token=YOUR_PROXY_AUTH_TOKEN`
3. Ensure **Auto-Fetch TMDb Metadata** is enabled and tap **Save & Test**.
4. KRAS TV will call `/api/tmdb/health` on your backend proxy, display **Connected** once verified, and automatically enrich all Movies, TV Series, Seasons, and Episodes imported from Xtream Codes or M3U playlists.
