# JWT keypair dev

Le chiavi RSA 2048 usate per firmare i JWT in dev. **Sono in `.gitignore`**:
non vanno mai committate.

## Genera al primo clone

```bash
cd backend/src/main/resources/jwt
openssl genrsa -out privateKey.pem 2048
openssl rsa -in privateKey.pem -pubout -out publicKey.pem
```

Senza questi file, Quarkus non parte (`MissingResourceException` su
`jwt/publicKey.pem` referenziato in `application.properties`).

## In produzione

Le chiavi prod stanno fuori dal classpath, in `/etc/dndsheets/jwt/` sul VPS,
montate come volume `:ro` nel container dell'app (vedi `docker-compose.yml`).
Le proprietà `mp.jwt.verify.publickey.location` /
`smallrye.jwt.sign.key.location` sono override-ate dal profilo `%prod` con
`file:/keys/...` (env `JWT_PUBLIC_KEY_PATH` / `JWT_PRIVATE_KEY_PATH`).
