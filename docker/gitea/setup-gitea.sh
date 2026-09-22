#!/usr/bin/env bash
# Setup idempotente di Gitea per PDForNotPDF:
#  - avvia il container
#  - crea l'utente admin (se manca)
#  - crea il repo "stampe-releases" con main inizializzato (se manca)
#
# Credenziali (usale anche nella config dell'app):
#   user:     pdforNotPdf
#   password: pdforNotPdf2026
set -euo pipefail

COMPOSE_DIR="$(cd "$(dirname "$0")" && pwd)"
GITEA_URL="http://localhost:3000"
USER="pdforCurrent"
# password: da ambiente, altrimenti generata random e salvata in .credentials (gitignored)
CRED_FILE="$(cd "$(dirname "$0")" && pwd)/.credentials"
PASSWORD="${GITEA_PASSWORD:-}"
USER_EXISTS=$(curl -s -o /dev/null -w "%{http_code}" -u "$USER:${PASSWORD:-x}" "$GITEA_URL/api/v1/user" 2>/dev/null || echo 000)
if [ -z "$PASSWORD" ] && [ -f "$CRED_FILE" ]; then
  PASSWORD=$(grep -oP '^GITEA_PASSWORD=\K.*' "$CRED_FILE" 2>/dev/null | head -1)
fi
if [ -z "$PASSWORD" ]; then
  PASSWORD=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)
  printf 'GITEA_PASSWORD=%s\n' "$PASSWORD" > "$CRED_FILE"
  chmod 600 "$CRED_FILE"
  echo "🔑 Password admin generata e salvata in $CRED_FILE (non committare)"
  echo "   Per l'app: export GITEA_PASSWORD=\"$PASSWORD\""
fi
EMAIL="pdforCurrent@local"
REPO="stampe-releases"

echo "▶ avvio container…"
docker compose -f "$COMPOSE_DIR/docker-compose.yml" up -d

echo "▶ attesa Gitea…"
for i in $(seq 1 60); do
  if curl -s -o /dev/null -m 2 "$GITEA_URL/api/health" || curl -s -o /dev/null -m 2 "$GITEA_URL"; then
    echo "  Gitea raggiungibile"; break
  fi
  sleep 2
done

echo "▶ creazione utente admin (se manca)…"
docker exec -u git --workdir /app/gitea pdforNotPdf-gitea gitea admin user create \
  --admin --username "$USER" --password "$PASSWORD" --email "$EMAIL" \
  --must-change-password=false 2>&1 | grep -v "already exists" || true

echo "▶ creazione repo $REPO (se manca)…"
CODE=$(curl -s -o /tmp/gitea-repo.json -w "%{http_code}" \
  -u "$USER:$PASSWORD" -X POST "$GITEA_URL/api/v1/user/repos" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"$REPO\",\"auto_init\":true,\"default_branch\":\"main\",\"private\":true}")
if [ "$CODE" = "201" ]; then
  echo "  repo creato"
elif [ "$CODE" = "409" ]; then
  echo "  repo già esistente"
else
  echo "  risposta inattesa ($CODE):"; cat /tmp/gitea-repo.json; exit 1
fi

echo
echo "✅ Gitea pronto: $GITEA_URL ($USER / $PASSWORD)"
echo "   Repo di produzione: $GITEA_URL/$USER/$REPO"
echo "   Config da impostare nell'app:"
echo "     app.release.remote.url=http://localhost:3000/$USER/$REPO.git"
echo "     app.release.remote.user=$USER"
echo "     app.release.remote.password=$PASSWORD"
echo "     app.release.remote.api=$GITEA_URL/api/v1"
