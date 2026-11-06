# KRAS TV — Official TMDb Backend Proxy & Multi-User Account Sync

This directory contains the production-ready backend code for **KRAS TV**:
1. **Cloudflare Worker** (`worker.js` / `wrangler.toml`): The primary backend deployed at `https://kras-tmdb-proxy.asdking01.workers.dev`.
2. **Node.js Express / Cloud Run Server** (`server.js` / `Dockerfile` / `deploy-cloud-run.sh`): Alternative containerized deployment option.

---

## 1. Cloudflare Worker Overview (`worker.js`)

- **Worker Name**: `kras-tmdb-proxy`
- **Canonical Worker URL**: `https://kras-tmdb-proxy.asdking01.workers.dev`
- **TMDb Official Auth Flow**: Uses official TMDb v3 authentication (`/3/authentication/token/new` and `/3/authentication/session/new`).
- **Zero Raw Credential Exposure**: Neither `TMDB_API_KEY` nor upstream raw TMDb `session_id` are ever sent to the Android app or exposed in deep link URLs.
- **Multi-User Isolation**: Independent KRAS TV user profiles are cryptographically isolated with HMAC-bound installation tokens (`kras_tok_...`).
- **One-Time Code Exchange**: Replay-resistant authorization codes (`kras_code_...`, 5-minute TTL) return the user safely via Android App Links.
- **Storage Flexibility**:
  - **Zero-Config Default**: Self-contained AES-256-GCM sealed tokens require **no database setup**; works immediately with just the `TMDB_API_KEY` secret.
  - **Cloudflare D1 (Optional)**: Automatically creates tables (`tmdb_auth_requests`, `tmdb_auth_codes`, `tmdb_user_sessions`, `tmdb_accounts`, `tmdb_sync_items`) on first request if binding `DB` is present.
  - **Cloudflare KV (Optional)**: Key-value caching if binding `TMDB_AUTH_KV` is present.

---

## 2. API Endpoints Reference

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/.well-known/assetlinks.json` | Android App Links verification file for `com.aistudio.krastv.vmzsqx`. |
| `GET` | `/health` / `/api/tmdb/test` / `/api/tmdb/health` | Live TMDb connectivity check & feature flags report. |
| `POST` | `/api/tmdb/auth/request-token` | Generates official TMDb request token and browser authorization URL. |
| `GET` | `/api/tmdb/auth/callback` | Browser callback after user approves on TMDb; redirects to KRAS TV App Link. |
| `GET` | `/app/tmdb-auth` | Android App Link web landing page that deep-links into KRAS TV. |
| `POST` | `/api/tmdb/auth/session` | Exchanges one-time code for opaque clientToken and account profile. |
| `GET` | `/api/tmdb/account` | Retrieves connected user's TMDb account profile. |
| `POST` | `/api/tmdb/auth/logout` | Revokes TMDb session upstream and invalidates client token. |
| `POST` | `/api/tmdb/sync` | Two-way sync for Favorites, Watchlist, and Ratings. |
| `POST` | `/api/tmdb/account/favorite` | Push single favorite toggle to TMDb. |
| `POST` | `/api/tmdb/account/watchlist` | Push single watchlist toggle to TMDb. |
| `POST` / `DELETE` | `/api/tmdb/account/rating` | Push or delete star rating on TMDb. |
| `GET` | `/api/tmdb/search/movie` | Direct TMDb movie search with IPTV query normalization. |
| `GET` | `/api/tmdb/search/tv` | Direct TMDb TV series search with IPTV query normalization. |
| `GET` | `/api/tmdb/movie` / `/api/tmdb/movie/:id` | Smart IPTV movie match + enriched metadata. |
| `GET` | `/api/tmdb/tv` / `/api/tmdb/tv/:id` | Smart IPTV TV series match + enriched metadata. |
| `GET` | `/api/tmdb/tv/:id/season/:season` | Full season episodes list with stills, ratings, and overviews. |
| `GET` | `/api/tmdb/match` | Generic IPTV stream matching endpoint. |

---

## 3. How to Deploy to Cloudflare Worker (From a Phone or Browser)

You do **not** need a computer to deploy this Cloudflare Worker. You can deploy it in 2 minutes directly from Chrome or Safari on your phone:

### Step 1: Log in to Cloudflare Dashboard
1. Open [dash.cloudflare.com](https://dash.cloudflare.com) on your phone or PC.
2. Log in to your Cloudflare account (`asdking01@gmail.com`).
3. Tap **Workers & Pages** in the left navigation sidebar.
4. Select your existing Worker: **`kras-tmdb-proxy`**.

### Step 2: Paste the Updated Worker Code
1. Tap the **Edit code** button (or **Quick Edit**).
2. Select all existing code in the editor and delete it.
3. Open `backend-tmdb-proxy/worker.js` (or `cloudflare-worker.js`), copy the entire file contents, and paste it into the Cloudflare code editor.
4. Tap **Deploy** (or **Save and Deploy**) in the top right corner.

### Step 3: Verify Your `TMDB_API_KEY` Secret
1. Go back to your Worker's overview page: **kras-tmdb-proxy → Settings → Variables and Secrets**.
2. Ensure you have an encrypted secret named **`TMDB_API_KEY`** containing your TMDb API key.
3. If it is already there, leave it as is. If missing, tap **Add** → **Secret**, name it `TMDB_API_KEY`, paste your TMDb key, and tap **Save**.

### Step 4 (Optional): Cloudflare D1 Database Binding
The Worker automatically works with zero configuration using cryptographically sealed tokens. If you wish to enable persistent D1 database storage:
1. In Cloudflare Dashboard, go to **Storage & Databases → D1 SQL Database**.
2. Tap **Create Database**, name it `kras-tmdb-db`, and tap **Create**.
3. Go back to **Workers & Pages → kras-tmdb-proxy → Settings → Bindings**.
4. Tap **Add** → **D1 database**:
   - Variable name: `DB`
   - D1 database: select `kras-tmdb-db`
5. Tap **Deploy**. The Worker will automatically create all tables on its next request.

---

## 4. How to Deploy via Wrangler CLI (Terminal)

If you have Node.js and Wrangler installed on your machine:

```bash
cd backend-tmdb-proxy

# 1. Run local automated tests
node --test worker.test.js

# 2. Deploy to Cloudflare
npx wrangler deploy

# 3. If TMDB_API_KEY is not yet set in Cloudflare:
npx wrangler secret put TMDB_API_KEY
```

---

## 5. Verification URLs

Once deployed, open these URLs in your browser or test with `curl`:

1. **Health & Feature Flags**:
   `https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/test`
   Expected response:
   ```json
   {
     "status": "ok",
     "ok": true,
     "tmdbConnected": true,
     "accountAuthSupported": true,
     "appLinksSupported": true,
     "oneTimeCodeAuth": true,
     "multiUserIsolated": true,
     "service": "KRAS TMDb Proxy"
   }
   ```

2. **Android App Links Asset Verification**:
   `https://kras-tmdb-proxy.asdking01.workers.dev/.well-known/assetlinks.json`
   Expected response: JSON array binding `com.aistudio.krastv.vmzsqx` to the Cloudflare Worker domain.

3. **Request Token Endpoint**:
   `POST https://kras-tmdb-proxy.asdking01.workers.dev/api/tmdb/auth/request-token`
   Returns official TMDb request token and authorization URL.
