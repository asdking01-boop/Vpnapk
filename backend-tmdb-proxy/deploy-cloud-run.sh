#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# KRAS TV — Automated Google Cloud Secret Manager + Cloud Run Deployment Script
# ==============================================================================
# Usage:
#   export GCP_PROJECT_ID="your-gcp-project-id"
#   export GCP_REGION="europe-west3"
#   export TMDB_API_KEY="your_real_tmdb_v3_api_key"
#   ./deploy-cloud-run.sh
# ==============================================================================

SERVICE_NAME="${SERVICE_NAME:-kras-tmdb-proxy}"
GCP_REGION="${GCP_REGION:-europe-west3}"
SECRET_NAME="${SECRET_NAME:-TMDB_API_KEY}"

if [[ -z "${GCP_PROJECT_ID:-}" ]]; then
  GCP_PROJECT_ID="$(gcloud config get-value project 2>/dev/null || true)"
fi

if [[ -z "${GCP_PROJECT_ID:-}" ]]; then
  echo "Error: GCP_PROJECT_ID is not set. Run 'export GCP_PROJECT_ID=your-project-id' first."
  exit 1
fi

echo "==> Using Google Cloud Project: ${GCP_PROJECT_ID} (Region: ${GCP_REGION})"
gcloud config set project "${GCP_PROJECT_ID}"

echo "==> Enabling required Google Cloud APIs (Run, Secret Manager, Cloud Build, Artifact Registry)..."
gcloud services enable \
  run.googleapis.com \
  secretmanager.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com

# Create or update TMDB_API_KEY in Google Cloud Secret Manager if TMDB_API_KEY is provided in env
if [[ -n "${TMDB_API_KEY:-}" ]]; then
  if gcloud secrets describe "${SECRET_NAME}" >/dev/null 2>&1; then
    echo "==> Adding new secret version to Secret Manager (${SECRET_NAME})..."
    printf "%s" "${TMDB_API_KEY}" | gcloud secrets versions add "${SECRET_NAME}" --data-file=-
  else
    echo "==> Creating secret ${SECRET_NAME} in Google Cloud Secret Manager..."
    printf "%s" "${TMDB_API_KEY}" | gcloud secrets create "${SECRET_NAME}" \
      --replication-policy="automatic" \
      --data-file=-
  fi
else
  echo "==> Note: TMDB_API_KEY env var not passed to script; assuming secret '${SECRET_NAME}' already exists in Secret Manager."
fi

# Grant the Cloud Run runtime service account access to read the secret
PROJECT_NUMBER="$(gcloud projects describe "${GCP_PROJECT_ID}" --format='value(projectNumber)')"
COMPUTE_SA="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"

echo "==> Granting Secret Manager Secret Accessor role to ${COMPUTE_SA}..."
gcloud secrets add-iam-policy-binding "${SECRET_NAME}" \
  --member="serviceAccount:${COMPUTE_SA}" \
  --role="roles/secretmanager.secretAccessor" >/dev/null

SECRET_FLAGS="TMDB_API_KEY=${SECRET_NAME}:latest"
if [[ -n "${PROXY_AUTH_TOKEN:-}" ]]; then
  AUTH_SECRET_NAME="KRAS_PROXY_AUTH_TOKEN"
  if gcloud secrets describe "${AUTH_SECRET_NAME}" >/dev/null 2>&1; then
    printf "%s" "${PROXY_AUTH_TOKEN}" | gcloud secrets versions add "${AUTH_SECRET_NAME}" --data-file=-
  else
    printf "%s" "${PROXY_AUTH_TOKEN}" | gcloud secrets create "${AUTH_SECRET_NAME}" \
      --replication-policy="automatic" \
      --data-file=-
  fi
  gcloud secrets add-iam-policy-binding "${AUTH_SECRET_NAME}" \
    --member="serviceAccount:${COMPUTE_SA}" \
    --role="roles/secretmanager.secretAccessor" >/dev/null
  SECRET_FLAGS="${SECRET_FLAGS},PROXY_AUTH_TOKEN=${AUTH_SECRET_NAME}:latest"
fi

echo "==> Deploying ${SERVICE_NAME} to Google Cloud Run..."
gcloud run deploy "${SERVICE_NAME}" \
  --source . \
  --region "${GCP_REGION}" \
  --platform managed \
  --allow-unauthenticated \
  --port 8080 \
  --memory 256Mi \
  --cpu 1 \
  --max-instances 10 \
  --set-secrets "${SECRET_FLAGS}"

SERVICE_URL="$(gcloud run services describe "${SERVICE_NAME}" --region "${GCP_REGION}" --format='value(status.url)')"
echo ""
echo "=============================================================================="
echo "Deployment complete!"
echo "Cloud Run Service URL: ${SERVICE_URL}"
echo "Health Check URL:      ${SERVICE_URL}/health"
echo "TMDb Check URL:        ${SERVICE_URL}/api/tmdb/health"
echo ""
echo "Next Step in KRAS TV Android App:"
echo "  1. Open KRAS TV -> Settings -> TMDb Metadata Proxy"
echo "  2. Enter: ${SERVICE_URL}"
echo "  3. Tap 'Save & Test' to verify live TMDb connectivity."
echo "=============================================================================="
