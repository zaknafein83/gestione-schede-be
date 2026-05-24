package it.fsisca.dndsheets.auth;

import com.password4j.Argon2Function;
import com.password4j.Password;
import com.password4j.types.Argon2;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Hash e verifica delle password con Argon2id.
 *
 * Parametri scelti come compromesso ragionevole sicurezza/latenza su hardware moderno:
 *  - memory: 65536 KiB (64 MiB)
 *  - iterations: 3
 *  - parallelism: 1
 *  - outputLen: 32 byte
 *  - tipo: id (Argon2id)
 *
 * Il salt e' generato automaticamente da Password4j e incluso nell'hash finale
 * (formato standard di Argon2: $argon2id$v=19$m=...,t=...,p=...$salt$hash).
 */
@ApplicationScoped
public class PasswordHasher {

    private static final Argon2Function ARGON2 =
            Argon2Function.getInstance(65_536, 3, 1, 32, Argon2.ID);

    public String hash(String plain) {
        return Password.hash(plain).with(ARGON2).getResult();
    }

    public boolean verify(String plain, String storedHash) {
        return Password.check(plain, storedHash).with(ARGON2);
    }
}
