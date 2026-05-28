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
#   - Retention LOCALE 14 giorni (env RETENTION_DAYS per override)
#   - Upload OFF-SITE su Backblaze B2 (opzionale, vedi sotto):
#       <dump> | gpg --symmetric -> .archive.gz.gpg
#                                -> rclone copy -> b2:<bucket>/<container>/
#     Retention REMOTA 30 giorni (env REMOTE_RETENTION_DAYS per override).
#   - Notifica Telegram (success+failure) se /etc/zaknafein/backup-env
#     contiene TG_TOKEN e TG_CHAT_ID
#   - Lock file in /tmp/backup-mongo.lock per evitare run paralleli
#   - Exit != 0 se anche solo un DB o un upload fallisce → cron manda mail
#
# Off-site setup (one-shot, lato VPS):
#   1) apt install rclone gnupg
#   2) rclone config  -> nuovo remote "b2" tipo Backblaze B2
#      (keyID + applicationKey con permessi readWrite sul bucket dedicato)
#   3) /etc/zaknafein/backup-env (mode 600, owner deploy):
#        TG_TOKEN=...
#        TG_CHAT_ID=...
#        B2_BUCKET=pg-mongo-backups       # nome del bucket creato su B2
#        B2_RCLONE_REMOTE=b2              # nome del remote in rclone.conf
#        BACKUP_GPG_PASSPHRASE='...'      # custodirla in password manager + carta
#        REMOTE_RETENTION_DAYS=30
#   Se B2_BUCKET o BACKUP_GPG_PASSPHRASE mancano, l'upload off-site viene
#   SKIPPATO senza errore (lo script si comporta come prima).
#
#   ATTENZIONE: senza la passphrase i .gpg sono inutilizzabili.
#   Salvarla anche fuori dal VPS.

set -euo pipefail

# Spostiamoci in una dir world-readable: se lo script viene lanciato come
# `sudo -u deploy` da una shell con cwd non leggibile da deploy (es. la home
# di ubuntu in mode 700), `find` esplode nel cleanup con "Failed to restore
# initial working directory: ... Permission denied" e `set -e` interrompe
# l'upload off-site. Per il cron questo non succede mai (parte da $HOME deploy).
cd /tmp

LOCK=/tmp/backup-mongo.lock
exec 200>"$LOCK"
flock -n 200 || { echo "backup-mongo già in esecuzione"; exit 0; }

RETENTION_DAYS="${RETENTION_DAYS:-14}"
REMOTE_RETENTION_DAYS="${REMOTE_RETENTION_DAYS:-30}"
BACKUP_ROOT="${BACKUP_ROOT:-/opt/backups}"
DATE=$(date -u +%F)

# Config opzionale (Telegram + off-site Backblaze B2 via rclone + GPG).
TG_TOKEN=""
TG_CHAT_ID=""
B2_BUCKET=""
B2_RCLONE_REMOTE="${B2_RCLONE_REMOTE:-b2}"
BACKUP_GPG_PASSPHRASE=""
if [[ -r /etc/zaknafein/backup-env ]]; then
  # shellcheck disable=SC1091
  source /etc/zaknafein/backup-env
fi

OFFSITE_ENABLED=0
if [[ -n "$B2_BUCKET" && -n "$BACKUP_GPG_PASSPHRASE" ]]; then
  if command -v rclone >/dev/null && command -v gpg >/dev/null; then
    OFFSITE_ENABLED=1
  else
    echo "WARN: off-site abilitato in env ma rclone o gpg non installati — skip upload"
  fi
fi

notify() {
  [[ -n "$TG_TOKEN" && -n "$TG_CHAT_ID" ]] || return 0
  curl -fsS -X POST "https://api.telegram.org/bot${TG_TOKEN}/sendMessage" \
    -d "chat_id=${TG_CHAT_ID}" \
    -d "text=$1" \
    -d "parse_mode=HTML" > /dev/null || true
}

# Cifra il file locale con GPG simmetrica (AES256), lo carica su B2 e
# applica la retention remota. Stampa "ok"/"FAIL" + path remoto.
# Ritorna 0 se tutto ok, 1 altrimenti. Non rimuove il dump locale.
upload_offsite() {
  local local_file="$1"     # /opt/backups/<container>/<DATE>.archive.gz
  local container="$2"
  local enc_file="${local_file}.gpg"
  local remote_path="${B2_RCLONE_REMOTE}:${B2_BUCKET}/${container}"

  # 1) GPG simmetrica → .archive.gz.gpg (passphrase via stdin, no tty)
  if ! gpg --batch --yes --pinentry-mode loopback \
           --passphrase "$BACKUP_GPG_PASSPHRASE" \
           --symmetric --cipher-algo AES256 \
           -o "$enc_file" "$local_file"; then
    echo "  upload FAIL: gpg encrypt"
    rm -f "$enc_file"
    return 1
  fi

  # 2) rclone copy del solo file .gpg verso b2:<bucket>/<container>/
  if ! rclone copy --quiet "$enc_file" "$remote_path/"; then
    echo "  upload FAIL: rclone copy"
    rm -f "$enc_file"
    return 1
  fi
  echo "  off-site ok ($(du -h "$enc_file" | cut -f1)) → ${remote_path}/$(basename "$enc_file")"

  # 3) Rimuove il .gpg locale (resta solo il .archive.gz, gestito da retention locale)
  rm -f "$enc_file"

  # 4) Retention remota: cancella i .gpg con mtime più vecchio di N giorni.
  # rclone delete usa --min-age sui FILE da CANCELLARE (più vecchi di).
  rclone delete --quiet --min-age "${REMOTE_RETENTION_DAYS}d" "$remote_path/" || true
  return 0
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
UPLOAD_FAILED=()
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

  # Retention LOCALE: cancella dump più vecchi di RETENTION_DAYS giorni.
  find "$DEST_DIR" -type f -name '*.archive.gz' -mtime "+${RETENTION_DAYS}" -delete

  # Upload OFF-SITE (opzionale). Un fallimento qui NON invalida il dump locale,
  # ma viene segnalato come errore per fare suonare il cron-mail.
  if [[ $OFFSITE_ENABLED -eq 1 ]]; then
    if ! upload_offsite "$DEST_FILE" "$CONT"; then
      UPLOAD_FAILED+=("$CONT")
    fi
  fi
done

# Notifica + exit code: dump fail e upload fail vengono trattati entrambi
# come "qualcosa non ha funzionato" (exit 1) ma sono mostrati separatamente
# per facilitare la diagnosi.
ERRORS=0
SUMMARY=""
if [[ ${#FAILED[@]} -gt 0 ]]; then
  ERRORS=1
  SUMMARY+="dump KO: $(IFS=, ; echo "${FAILED[*]}")"
fi
if [[ ${#UPLOAD_FAILED[@]} -gt 0 ]]; then
  ERRORS=1
  [[ -n "$SUMMARY" ]] && SUMMARY+=" · "
  SUMMARY+="upload KO: $(IFS=, ; echo "${UPLOAD_FAILED[*]}")"
fi

if [[ $ERRORS -ne 0 ]]; then
  notify "❌ <b>backup-mongo $DATE</b> ${SUMMARY}"
  exit 1
fi

OFFSITE_NOTE=""
[[ $OFFSITE_ENABLED -eq 1 ]] && OFFSITE_NOTE=" + off-site"
notify "✅ <b>backup-mongo $DATE</b> ok (${#CONTAINERS[@]} db${OFFSITE_NOTE})"
exit 0
