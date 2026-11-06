# KRAS TV — Secure Server-Side TMDb Proxy

This directory contains the server-side proxy for the KRAS TV Android application. It ensures **`TMDB_API_KEY` is never embedded in client-side Kotlin code or packaged inside the Android APK**.

## Endpoints

- `GET /api/tmdb/health` — Verifies that `TMDB_API_KEY` is configured on the server and performs a live connectivity check against TMDb `/3/configuration`.
- `GET /api/tmdb/movie?title=<title>&year=<year>&tmdbId=<id>&language=<lang>` — Searches TMDb and returns movie poster, backdrop, overview/description, rating, release year, genres, cast, and director.
- `GET /api/tmdb/tv?title=<title>&year=<year>&tmdbId=<id>&language=<lang>` — Searches TMDb and returns TV series poster, backdrop, overview/description, rating, release year, genres, cast, creator, and seasons list.
- `GET /api/tmdb/tv/:tvId/season/:seasonNumber?language=<lang>` — Returns episode titles, overviews, still images, ratings, and runtimes for a specific season.

## Deployment Instructions

### Option 1: Google Cloud Run (Recommended)
```bash
gcloud run deploy kras-tmdb-proxy \
  --source ./backend-tmdb-proxy \
  --region europe-west3 \
  --allow-unauthenticated \
  --set-env-vars="TMDB_API_KEY=YOUR_REAL_TMDB_API_KEY"
```

### Option 2: Docker / Render / Railway / Fly.io
1. Point your hosting service to the `/backend-tmdb-proxy` directory.
2. Set the server-side environment variable:
   - `TMDB_API_KEY`: Your TMDb v3 API Key or v4 Read Access Bearer Token.
3. Copy your deployed HTTPS URL (e.g., `https://kras-tmdb-proxy-xxxx.run.app`) and paste it into **KRAS TV -> Settings -> TMDb Metadata Proxy URL** (or set `TMDB_PROXY_BASE_URL` in `app/build.gradle.kts`).
