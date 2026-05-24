# Gestione Schede D&D 5e — backend

Applicazione web (e in seguito mobile) per gestire schede personaggio della 5ª edizione di Dungeons & Dragons. Ogni utente registrato può creare e modificare più personaggi, tirare dadi, tracciare HP / slot incantesimi / condizioni durante il gioco, e condividere una scheda in sola lettura tramite link pubblico.

> **Questo è il repo backend** (Quarkus + MongoDB). Il frontend Flutter Web vive in [`zaknafein83/gestione-schede-fe`](https://github.com/zaknafein83/gestione-schede-fe). Entrambi vengono serviti dietro `https://pg.zaknafein.ovh` (vedi sez. **Deploy**).

---

## Stack tecnico

| Strato            | Tecnologia                                                  |
|-------------------|-------------------------------------------------------------|
| Backend           | Java 25 (JBR di IntelliJ) + Quarkus 3.35.4, **Quarkus REST** in stile imperativo/sincrono |
| Persistenza       | MongoDB con Panache Mongo (API classica, non reattiva)      |
| Storage immagini  | MongoDB **GridFS** (avatar utente, ritratti personaggio)    |
| Auth              | JWT access (15 min) + refresh (30 gg), verifica email       |
| Email             | SMTP (MailHog in dev, provider reale in prod)               |
| Frontend          | Flutter — **Web** in MVP 1-5, mobile in MVP-6               |
| Networking FE     | dio + interceptor refresh-token                             |
| Stato FE          | Riverpod (da confermare)                                    |
| Routing FE        | go_router                                                   |
| Storage token FE  | `flutter_secure_storage` (compatibile web + mobile)         |
| Dev env           | docker-compose: Mongo + MailHog                             |

**Perché Quarkus REST in stile imperativo:** la decisione di scope era "REST classico, non reattivo" intesa come **stile imperativo/sincrono nel codice** (no `Uni<T>` / `Multi<T>`). L'estensione si chiama `quarkus-rest` (la "nuova generazione", ex RESTEasy Reactive) — è il motore raccomandato per i nuovi progetti su Quarkus 3.x; quello storicamente chiamato "RESTEasy Classic" (`quarkus-resteasy`) è in maintenance mode e non si usa per progetti nuovi. `quarkus-rest` supporta endpoint sincroni nativamente, quindi soddisfa il requisito di leggibilità/debug semplice senza richiedere stile reattivo. Stessa logica lato Mongo: usiamo `quarkus-mongodb-panache` (API sincrona), non `quarkus-mongodb-panache-reactive`.

---

## Roadmap MVP (web-first)

| Fase  | Contenuto                                                            | Piattaforma     |
|-------|----------------------------------------------------------------------|-----------------|
| MVP-1 | Auth completa + gestione profilo + CRUD scheda **statica** (no calcoli) | Flutter Web     |
| MVP-2 | Calcoli automatici (modificatori, CA, bonus competenza, slot) + dice roller | Flutter Web     |
| MVP-3 | Combat tracking (HP, slot incantesimi, condizioni, riposi) + cronologia tiri | Flutter Web     |
| MVP-4 | Condivisione read-only via link pubblico + export JSON               | Flutter Web     |
| MVP-5 | Export PDF + dark mode + internazionalizzazione EN                   | Flutter Web     |
| MVP-6 | Build mobile iOS/Android dalla stessa codebase Flutter               | Flutter Mobile  |

Linea guida tecnica: nelle fasi web (MVP 1-5) si privilegiano widget e librerie già compatibili anche con mobile, per non dover riscrivere lo strato di networking/storage al MVP-6.

---

## Funzionalità

### 1. Account & autenticazione
- Registrazione con email + password (≥ 10 caratteri, almeno 1 maiuscola e 1 numero).
- Email di verifica con token monouso, scadenza 24 ore.
- Login → coppia access/refresh token.
- Logout (revoca server-side del refresh token).
- Password dimenticata → email con link di reset, token monouso.
- Cambio password da profilo (richiede password attuale).
- Rate limit su `/auth/*`.
- Password hashate con **Argon2id**.

### 2. Profilo utente
- Username pubblico univoco, nome visualizzato, bio (max 500 caratteri).
- Avatar (upload + crop quadrato, max 2 MB → GridFS).
- Visualizzazione data registrazione e numero personaggi creati.
- Eliminazione account: soft delete + 30 giorni di grazia, poi hard delete con cascata sulle schede.

### 3. Schede personaggio
Replica fedele della scheda PHB 5ed; **tutti i campi modificabili**.

**Anagrafica:** nome, razza/sottorazza, classe/sottoclasse, livello, background, allineamento, esperienza, ispirazione, ritratto (GridFS).

**Caratteristiche:** i 6 ability score (STR/DEX/CON/INT/WIS/CHA), modificatori calcolati automaticamente.

**Combattimento:** CA, iniziativa, velocità, HP attuali/massimi/temporanei, dadi vita, tiri salvezza contro morte, bonus competenza (automatico dal livello).

**Tiri salvezza & abilità:** 6 TS + 18 skill, flag "competenza" / "esperto" per ciascuna → bonus calcolato in automatico.

**Attacchi & incantesimi:** lista attacchi (nome, bonus, danno, tipo), classe da incantatore, CD incantesimo (auto), bonus attacco incantesimo (auto), slot per livello (attuali/massimi), lista incantesimi conosciuti/preparati.

**Equipaggiamento:** monete (mr/ma/me/mo/mp), inventario libero (oggetto, quantità, peso, note), peso totale calcolato.

**Tratti:** personalità, ideali, legami, difetti, lingue, competenze in armi/armature/strumenti, capacità di classe e razza (testo libero o lista).

**Note:** backstory, alleati e organizzazioni, simbolo, descrizione fisica, note libere.

**Operazioni:** CRUD completo, **duplica scheda**, esportazione JSON. Export PDF in MVP-5.

### 4. Dice roller & combat tracking
- Notazione standard (`2d6+3`, `1d20`, `4d6kh3`, ecc.).
- Cronologia ultimi 50 tiri per scheda (TTL 90 gg).
- Quick-roll dai tiri salvezza/abilità/attacchi (tap → tira con bonus precompilato).
- Modalità "in gioco": HP +/-, slot incantesimo consumati, condizioni attive (lista PHB: avvelenato, paralizzato, ecc.).
- Riposo breve / riposo lungo (reset di HP e slot secondo le regole).
- Log eventi di sessione (timestamp, tipo, dettaglio).

### 5. Condivisione read-only
- Da una scheda: "Genera link condivisione" → URL pubblico con token random.
- Endpoint pubblico (no auth) che serve la scheda in sola lettura.
- Revoca del link dal proprietario; la rigenerazione invalida il vecchio.
- Contatore "X persone hanno visto questo link".

---

## Modello dati (Mongo)

| Collezione            | Note                                                                              |
|-----------------------|-----------------------------------------------------------------------------------|
| `users`               | `_id`, email, passwordHash, emailVerified, username, displayName, bio, avatarFileId, createdAt, deletedAt |
| `email_verifications` | TTL index                                                                         |
| `password_resets`     | TTL index                                                                         |
| `refresh_tokens`      | TTL index + revocazione                                                           |
| `characters`          | documento denormalizzato (~50–80 campi) per evitare join, `ownerId`, timestamps   |
| `dice_logs`           | `_id`, characterId, expression, result, breakdown, rolledAt (TTL 90 gg)           |
| `share_links`         | `_id`, characterId, token, createdAt, revokedAt, viewCount                        |
| `fs.files` / `fs.chunks` | GridFS standard                                                                |

**Indici previsti:** `users.email` univoco, `users.username` univoco, `characters.ownerId`, `share_links.token` univoco.

---

## API REST (sketch)

### Auth
- `POST /auth/register`
- `POST /auth/verify-email`
- `POST /auth/login`
- `POST /auth/refresh`
- `POST /auth/logout`
- `POST /auth/password-reset/request`
- `POST /auth/password-reset/confirm`

### Profilo
- `GET /me`
- `PATCH /me`
- `POST /me/avatar`
- `POST /me/change-password`
- `DELETE /me`

### Schede
- `GET /characters`
- `POST /characters`
- `GET /characters/{id}`
- `PATCH /characters/{id}`
- `DELETE /characters/{id}`
- `POST /characters/{id}/duplicate`
- `POST /characters/{id}/portrait`

### Gioco
- `POST /characters/{id}/roll`
- `GET /characters/{id}/rolls`
- `POST /characters/{id}/rest/short`
- `POST /characters/{id}/rest/long`

### Condivisione
- `POST /characters/{id}/share`
- `DELETE /characters/{id}/share`
- `GET /public/characters/{token}` (no auth)

Risposte JSON. Errori in formato **RFC 7807** (`application/problem+json`).

---

## Aspetti trasversali

- **Validazione:** Bean Validation (Hibernate Validator) lato backend, validazione gemella in Flutter.
- **Logging:** structured JSON, livelli configurabili.
- **Sicurezza:** rate limit su `/auth/*`, Argon2id per le password, CORS configurato, security headers.
- **Testing:**
  - Backend: JUnit 5 + RestAssured + Testcontainers (Mongo).
  - Frontend: widget test + integration test.
- **CI:** build Quarkus + immagine Docker; build Flutter Web (e in seguito APK).
- **i18n:** italiano come lingua primaria con i18n predisposta sin dall'inizio; inglese aggiunto al MVP-5.

---

## Struttura del repository

```
gestione-schede-be/         # questo repo
├── src/main/java/...
├── src/main/resources/application.properties
├── pom.xml
├── Dockerfile               # multi-stage Maven + JRE alpine, Java 25
├── docker-compose.yml       # Compose di PROD (mongo + app GHCR, usato sul VPS)
├── docker-compose.dev.yml   # Mongo 8 + Mailpit per dev locale
├── deploy/                  # Asset di deploy (vhost nginx, ecc.)
├── tools/                   # Script utility (translate_spells, backup-mongo)
├── .github/workflows/       # CI/CD: deploy.yml
├── activate-env.ps1         # imposta JAVA_HOME/PATH nella sessione PowerShell
└── README.md

gestione-schede-fe/         # repo separato (Flutter Web)
└── ...
```

### Comandi di sviluppo

**Backend (Quarkus dev mode con hot reload):**
```powershell
. .\activate-env.ps1
.\mvnw.cmd quarkus:dev
```

**Frontend**: vedi [`gestione-schede-fe`](https://github.com/zaknafein83/gestione-schede-fe).

**Stack di supporto (Mongo + Mailpit):**
```powershell
docker compose -f docker-compose.dev.yml up -d        # avvia
docker compose -f docker-compose.dev.yml down         # ferma
docker compose -f docker-compose.dev.yml logs -f      # tail dei log
```

Il file `docker-compose.yml` nella root è invece il **compose di produzione** (usato sul VPS dal workflow CI/CD, vedi sezione "Deploy"). NON usarlo in locale a meno di non voler simulare l'ambiente prod.

### Porte locali

| Servizio              | Porta | Note                                                    |
|-----------------------|-------|---------------------------------------------------------|
| Backend Quarkus       | 8090  | spostato dalla 8080 per evitare conflitto con altro container locale |
| Frontend Flutter Web  | 8081  | fissata via `flutter run --web-port=8081`               |
| MongoDB               | 27017 | container `dndsheets-mongo`                             |
| Mailpit SMTP          | 1025  | per le email in dev                                     |
| Mailpit UI            | 8025  | http://localhost:8025                                   |
| Swagger UI            | 8090/q/swagger-ui | API explorer del backend                    |

---

## Deploy (produzione)

`gestione-schede` è onboardato nell'ecosistema CI/CD `zaknafein.ovh`. Backend e frontend (repo separato) si deployano automaticamente a ogni push su `main` via GitHub Actions → GHCR → VPS. Il pattern è documentato in `PLAYBOOK_DEPLOY.md` e `VPS-RUNBOOK.md` (in `~/Documents/Progetti/` sulla macchina dello sviluppatore).

### Topologia

```
pg.zaknafein.ovh (nginx + Let's Encrypt)
  ├── /          → /var/www/pg/                       (Flutter Web statico, deploy via rsync)
  └── /api/*     → 127.0.0.1:8082                      (container app Quarkus)
                       │
                       └── (compose interno) mongo:27017
```

- **Sottodominio**: `pg.zaknafein.ovh`
- **Porta interna backend**: `8082` (registro: `/opt/.ports-registry`)
- **Cartella VPS**: `/opt/gestione-schede/` (compose + `.env` + `.deploy-tag`)
- **Image GHCR**: `ghcr.io/zaknafein83/gestione-schede-be`

### File del repo coinvolti

| File | Cosa fa |
|---|---|
| [`Dockerfile`](Dockerfile) | Multi-stage Maven + JRE alpine, Java 25, fast-jar. |
| [`docker-compose.yml`](docker-compose.yml) | Compose di PROD (mongo + app GHCR). Sincronizzato su `/opt/gestione-schede/docker-compose.yml` dal workflow. |
| [`docker-compose.dev.yml`](docker-compose.dev.yml) | Compose di DEV (mongo + mailpit con porte esposte). Solo locale. |
| [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) | Test + build + push GHCR + `ssh deploy + compose pull/up` + health check. |
| [`deploy/nginx-pg.conf`](deploy/nginx-pg.conf) | Vhost nginx "split" (statico + `/api/*` proxy). Va installato a mano sul VPS la prima volta. |
| [`tools/backup-mongo.sh`](tools/backup-mongo.sh) | Backup giornaliero `mongodump` con retention 14gg. Va installato a mano in `~deploy/bin/` sul VPS. |

Il workflow del frontend (Flutter Web build + rsync su `/var/www/pg/`) vive nel repo [`gestione-schede-fe`](https://github.com/zaknafein83/gestione-schede-fe).

### Setup VPS la prima volta (one-shot)

```bash
ssh deploy@57.131.21.62

# 1) Cartella progetto (richiede sudo: /opt/ è root-owned)
sudo install -d -o deploy -g deploy -m 755 /opt/gestione-schede

# 2) Genera password Mongo random + popola /opt/gestione-schede/.env
MONGO_PW=$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)
sudo -u deploy tee /opt/gestione-schede/.env > /dev/null <<EOF
TAG=latest
MONGO_PASSWORD=${MONGO_PW}
APP_BASE_URL=https://pg.zaknafein.ovh
ADMIN_BOOTSTRAP_EMAIL=franksisca@gmail.com
# SMTP (Resend di default)
SMTP_USERNAME=resend
SMTP_PASSWORD=re_XXXXXXXXXXXXXXXXXXXXXX
SMTP_FROM=noreply@pg.zaknafein.ovh
EOF
sudo chmod 600 /opt/gestione-schede/.env

# 3) Keypair JWT (RS256) — usate dal backend in lettura tramite volume bind
sudo install -d -o deploy -g deploy -m 750 /etc/dndsheets/jwt
sudo -u deploy openssl genrsa -out /etc/dndsheets/jwt/privateKey.pem 2048
sudo -u deploy openssl rsa -in /etc/dndsheets/jwt/privateKey.pem \
  -pubout -out /etc/dndsheets/jwt/publicKey.pem
sudo chmod 600 /etc/dndsheets/jwt/*.pem

# 4) Cartella web frontend
sudo install -d -o deploy -g www-data -m 755 /var/www/pg
echo "<h1>Coming soon</h1>" | sudo tee /var/www/pg/index.html > /dev/null

# 5) Vhost nginx (NON usare new-subdomain.sh: è proxy-only, qui serve "split")
sudo cp /home/deploy/gestione-schede/deploy/nginx-pg.conf \
        /etc/nginx/sites-available/pg.conf
sudo ln -s ../sites-available/pg.conf /etc/nginx/sites-enabled/pg.conf

# 6) DNS: aggiungi record A pg.zaknafein.ovh → 57.131.21.62 dalla UI OVH
#    Attendi propagazione (qualche minuto), poi:
dig +short pg.zaknafein.ovh   # deve restituire 57.131.21.62

# 7) Cert Let's Encrypt
sudo certbot certonly --nginx -d pg.zaknafein.ovh \
  -m franksisca@gmail.com --agree-tos --non-interactive
sudo nginx -t && sudo systemctl reload nginx

# 8) Registro porte
echo "8082  gestione-schede   pg.zaknafein.ovh" | sudo tee -a /opt/.ports-registry

# 9) Backup Mongo: copia tools/backup-mongo.sh in ~deploy/bin/ e schedula
sudo -u deploy cp /home/deploy/gestione-schede/tools/backup-mongo.sh ~deploy/bin/
sudo -u deploy chmod +x ~deploy/bin/backup-mongo.sh
( sudo -u deploy crontab -l 2>/dev/null; echo "15 3 * * * /home/deploy/bin/backup-mongo.sh >> /var/log/backup-mongo.log 2>&1" ) | sudo -u deploy crontab -

# 10) Secrets GitHub (una sola volta per CIASCUN repo): VPS_HOST, VPS_USER, VPS_SSH_KEY
~/Documents/Progetti/scripts/setup-repo.sh gestione-schede-be
~/Documents/Progetti/scripts/setup-repo.sh gestione-schede-fe
```

Dopo questi step, ogni push su `main` su uno dei due repo triggera il deploy del rispettivo componente.

### Smoke test post-deploy

```bash
curl -fsS https://pg.zaknafein.ovh/api/q/health/ready   # 200 → backend ok
curl -I    https://pg.zaknafein.ovh                      # 200 → frontend ok
```

### Rollback (al SHA precedente)

```bash
ssh deploy@57.131.21.62
cd /opt/gestione-schede
TAG=<sha-precedente> docker compose pull app
TAG=<sha-precedente> docker compose up -d
echo "TAG=<sha-precedente>" > .deploy-tag
```

### Variabili d'ambiente in `/opt/gestione-schede/.env`

| Variabile | Obbligatoria | Esempio | Note |
|---|---|---|---|
| `TAG` | sì | `latest` o uno SHA | gestita dal workflow, manuale solo per rollback |
| `MONGO_PASSWORD` | sì | random 24 char | usata sia dal container Mongo che dall'URI del backend |
| `APP_BASE_URL` | sì | `https://pg.zaknafein.ovh` | usato per CORS e link nelle email |
| `ADMIN_BOOTSTRAP_EMAIL` | no | `franksisca@gmail.com` | promosso ad ADMIN al primo boot. Vuoto = disabilitato |
| `SMTP_USERNAME` | sì | `resend` | username SMTP (Resend usa il letterale `resend`) |
| `SMTP_PASSWORD` | sì | API key del provider | non commitarla |
| `SMTP_FROM` | sì | `noreply@pg.zaknafein.ovh` | mittente delle email |
| `SMTP_HOST` | no | `smtp.resend.com` | default Resend, override per altri provider |
| `SMTP_PORT` | no | `465` | |
| `SMTP_START_TLS` | no | `REQUIRED` | |
| `SMTP_AUTH` | no | `PLAIN` | |

---

## Decisioni di scope esplicitamente fuori dal MVP iniziale
- Sistema di **campagne con ruoli DM/Player** → eventuale v2.
- **Editing collaborativo** in tempo reale → eventuale v2.
- **OAuth / social login** → eventuale aggiunta successiva.
- **Storage immagini su S3** → si valuta solo se GridFS dovesse mostrare limiti di scala.
