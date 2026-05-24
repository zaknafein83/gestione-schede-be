#!/usr/bin/env bash
# backup-mongo.sh — backup di tutti i container MongoDB attivi sul VPS.
#
# Modello parallelo a ~deploy/bin/backup-postgres.sh (vedi VPS-RUNBOOK 6.3).
# Da installare in /home/deploy/bin/backup-mongo.sh sul VPS e schedulare via
# crontab di `deploy` (es. 03:15 UTC per non sovrapporsi al postgres).
#
#   chmod +x ~deploy/bin/backup-mongo.sh
#   crontab -e
#     15 3 * * * /home/deploy/bin/backup-mongo.sh >> /var/log/backup-mongo.log 2>&1
#
# Cosa fa:
#   - Auto-discovery: tutti i container con image "mongo:*"
#   - Per ognuno: docker exec ... mongodump --archive --gzip
#     -> /opt/backups/<container>/<YYYY-MM-DD>.archive.gz
#   - Retention 14 giorni (env RETENTION_DAYS per override)
#   - Notifica Telegram (success+failure) se /etc/zaknafein/backup-env
#     contiene TG_TOKEN e TG_CHAT_ID
#   - Lock file in /tmp/backup-mongo.lock per evitare run paralleli
#   - Exit != 0 se anche solo un DB fallisce → cron manda mail

set -euo pipefail

LOCK=/tmp/backup-mongo.lock
exec 200>"$LOCK"
flock -n 200 || { echo "backup-mongo già in esecuzione"; exit 0; }

RETENTION_DAYS="${RETENTION_DAYS:-14}"
BACKUP_ROOT="${BACKUP_ROOT:-/opt/backups}"
DATE=$(date -u +%F)

# Telegram config opzionale.
TG_TOKEN=""
TG_CHAT_ID=""
if [[ -r /etc/zaknafein/backup-env ]]; then
  # shellcheck disable=SC1091
  source /etc/zaknafein/backup-env
fi

notify() {
  [[ -n "$TG_TOKEN" && -n "$TG_CHAT_ID" ]] || return 0
  curl -fsS -X POST "https://api.telegram.org/bot${TG_TOKEN}/sendMessage" \
    -d "chat_id=${TG_CHAT_ID}" \
    -d "text=$1" \
    -d "parse_mode=HTML" > /dev/null || true
}

# Lista container con image che inizia per "mongo:" (mongo:8, mongo:7, …).
mapfile -t CONTAINERS < <(
  docker ps --format '{{.Names}}|{{.Image}}' \
    | awk -F'|' 'tolower($2) ~ /^mongo:/ {print $1}'
)

if [[ ${#CONTAINERS[@]} -eq 0 ]]; then
  echo "Nessun container Mongo attivo."
  exit 0
fi

FAILED=()
for CONT in "${CONTAINERS[@]}"; do
  DEST_DIR="${BACKUP_ROOT}/${CONT}"
  DEST_FILE="${DEST_DIR}/${DATE}.archive.gz"
  mkdir -p "$DEST_DIR"

  # Credenziali root dell'istanza, lette dall'env del container.
  USER=$(docker inspect "$CONT" \
    --format '{{range .Config.Env}}{{println .}}{{end}}' \
    | grep -E '^MONGO_INITDB_ROOT_USERNAME=' | head -1 | cut -d= -f2-)
  PASS=$(docker inspect "$CONT" \
    --format '{{range .Config.Env}}{{println .}}{{end}}' \
    | grep -E '^MONGO_INITDB_ROOT_PASSWORD=' | head -1 | cut -d= -f2-)

  echo "[$(date -u +%H:%M:%S)] dump $CONT → $DEST_FILE"

  if [[ -n "$USER" && -n "$PASS" ]]; then
    DUMP_CMD=(mongodump --archive --gzip
              --username "$USER" --password "$PASS"
              --authenticationDatabase admin)
  else
    DUMP_CMD=(mongodump --archive --gzip)
  fi

  if docker exec "$CONT" "${DUMP_CMD[@]}" > "$DEST_FILE" 2>/tmp/backup-mongo.err; then
    echo "  ok ($(du -h "$DEST_FILE" | cut -f1))"
  else
    echo "  FAIL"
    cat /tmp/backup-mongo.err >&2
    FAILED+=("$CONT")
    rm -f "$DEST_FILE"
    continue
  fi

  # Retention: cancella dump più vecchi di RETENTION_DAYS giorni.
  find "$DEST_DIR" -type f -name '*.archive.gz' -mtime "+${RETENTION_DAYS}" -delete
done

if [[ ${#FAILED[@]} -gt 0 ]]; then
  notify "❌ <b>backup-mongo $DATE</b> falliti: $(IFS=, ; echo "${FAILED[*]}")"
  exit 1
fi

notify "✅ <b>backup-mongo $DATE</b> ok (${#CONTAINERS[@]} db)"
exit 0
