package it.fsisca.dndsheets.auth;

import com.mongodb.client.MongoClient;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.parsing.Parser;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Test feature password reset (forgot-password + reset-password).
 *
 * Pattern: stesso del flow verify-email — token in chiaro nel link via mail,
 * hash in DB. Validità 1h. Anti-enumeration: 204 anche per email inesistente.
 */
@QuarkusTest
@DisplayName("Feature password reset")
class PasswordResetTest {

    private static final Pattern VERIFY_TOKEN_PATTERN = Pattern.compile("verify-email\\?token=([A-Za-z0-9_-]+)");
    private static final Pattern RESET_TOKEN_PATTERN  = Pattern.compile("reset-password\\?token=([A-Za-z0-9_-]+)");

    @Inject MongoClient mongoClient;
    @Inject MockMailbox mailbox;

    @ConfigProperty(name = "quarkus.mongodb.database") String dbName;

    @BeforeAll
    static void registerProblemJsonParser() {
        RestAssured.registerParser("application/problem+json", Parser.JSON);
    }

    @BeforeEach
    void resetState() {
        var db = mongoClient.getDatabase(dbName);
        db.getCollection("users").deleteMany(new Document());
        db.getCollection("email_verifications").deleteMany(new Document());
        db.getCollection("password_reset_tokens").deleteMany(new Document());
        db.getCollection("refresh_tokens").deleteMany(new Document());
        mailbox.clear();
    }

    @Test
    @DisplayName("POST /auth/forgot-password con email esistente: 204 + email inviata con link reset")
    void forgotPasswordExistingUser() {
        registerAndVerify("frank@example.com", "frank");

        given().contentType(ContentType.JSON)
                .body("{\"email\":\"frank@example.com\"}")
                .when().post("/auth/forgot-password")
                .then().statusCode(204);

        String token = extractResetToken("frank@example.com");
        // Token deve essere lungo (43 char di base64url, no padding)
        org.junit.jupiter.api.Assertions.assertTrue(token.length() >= 32);
    }

    @Test
    @DisplayName("POST /auth/forgot-password con email inesistente: 204 (anti-enumeration), nessuna mail")
    void forgotPasswordUnknownEmail() {
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"ghost@example.com\"}")
                .when().post("/auth/forgot-password")
                .then().statusCode(204);

        // Nessuna mail inviata: la mockmailbox non ha messaggi per ghost
        org.junit.jupiter.api.Assertions.assertTrue(
                mailbox.getMailMessagesSentTo("ghost@example.com").isEmpty());
    }

    @Test
    @DisplayName("POST /auth/forgot-password con email non verificata: 204, nessuna mail")
    void forgotPasswordUnverifiedAccount() {
        // Registra ma NON verifica
        given().contentType(ContentType.JSON)
                .body("""
                      {"email":"frank@example.com","password":"Password123","username":"frank","displayName":"frank","acceptPrivacy":true,"declareMinAge":true}
                      """)
                .when().post("/auth/register").then().statusCode(201);
        mailbox.clear(); // scarta la verifica email

        given().contentType(ContentType.JSON)
                .body("{\"email\":\"frank@example.com\"}")
                .when().post("/auth/forgot-password")
                .then().statusCode(204);

        // Nessuna mail di reset perché l'account non è verificato
        boolean anyReset = mailbox.getMailMessagesSentTo("frank@example.com").stream()
                .anyMatch(m -> m.getSubject().toLowerCase().contains("reset"));
        org.junit.jupiter.api.Assertions.assertFalse(anyReset);
    }

    @Test
    @DisplayName("POST /auth/reset-password con token valido: la nuova password funziona, la vecchia no")
    void resetPasswordHappyPath() {
        registerAndVerify("frank@example.com", "frank");

        // Login con password vecchia ok
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"frank@example.com\",\"password\":\"Password123\"}")
                .when().post("/auth/login").then().statusCode(200);

        // Avvia reset
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"frank@example.com\"}")
                .when().post("/auth/forgot-password").then().statusCode(204);

        String token = extractResetToken("frank@example.com");

        // Reset password
        given().contentType(ContentType.JSON)
                .body("{\"token\":\"" + token + "\",\"password\":\"NewPassword456\"}")
                .when().post("/auth/reset-password")
                .then().statusCode(204);

        // Login con vecchia password: fallisce
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"frank@example.com\",\"password\":\"Password123\"}")
                .when().post("/auth/login")
                .then().statusCode(401)
                .body("code", equalTo("INVALID_CREDENTIALS"));

        // Login con nuova password: ok
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"frank@example.com\",\"password\":\"NewPassword456\"}")
                .when().post("/auth/login")
                .then().statusCode(200);
    }

    @Test
    @DisplayName("POST /auth/reset-password con token scaduto/inesistente: 400")
    void resetPasswordInvalidToken() {
        given().contentType(ContentType.JSON)
                .body("{\"token\":\"x12345678901234567890123456789012\",\"password\":\"NewPassword456\"}")
                .when().post("/auth/reset-password")
                .then().statusCode(400)
                .body("code", equalTo("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("POST /auth/reset-password con token già usato: 400")
    void resetPasswordTokenReuse() {
        registerAndVerify("frank@example.com", "frank");

        given().contentType(ContentType.JSON)
                .body("{\"email\":\"frank@example.com\"}")
                .when().post("/auth/forgot-password").then().statusCode(204);

        String token = extractResetToken("frank@example.com");

        given().contentType(ContentType.JSON)
                .body("{\"token\":\"" + token + "\",\"password\":\"NewPassword456\"}")
                .when().post("/auth/reset-password").then().statusCode(204);

        // Secondo uso dello stesso token: deve fallire
        given().contentType(ContentType.JSON)
                .body("{\"token\":\"" + token + "\",\"password\":\"YetAnother789\"}")
                .when().post("/auth/reset-password")
                .then().statusCode(400)
                .body("code", equalTo("EXPIRED_OR_USED_TOKEN"));
    }

    @Test
    @DisplayName("forgot-password ripetuta invalida i token precedenti dello stesso utente")
    void forgotPasswordSupersedesPrevious() {
        registerAndVerify("frank@example.com", "frank");

        // Prima richiesta
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"frank@example.com\"}")
                .when().post("/auth/forgot-password").then().statusCode(204);
        String token1 = extractResetToken("frank@example.com");

        // Seconda richiesta — invalida la prima
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"frank@example.com\"}")
                .when().post("/auth/forgot-password").then().statusCode(204);
        String token2 = extractResetToken("frank@example.com");

        assertNotEquals(token1, token2);

        // Token1 ora non funziona più
        given().contentType(ContentType.JSON)
                .body("{\"token\":\"" + token1 + "\",\"password\":\"NewPassword456\"}")
                .when().post("/auth/reset-password")
                .then().statusCode(400)
                .body("code", equalTo("EXPIRED_OR_USED_TOKEN"));

        // Token2 funziona
        given().contentType(ContentType.JSON)
                .body("{\"token\":\"" + token2 + "\",\"password\":\"NewPassword456\"}")
                .when().post("/auth/reset-password")
                .then().statusCode(204);
    }

    @Test
    @DisplayName("Reset password revoca tutti i refresh token attivi (logout su tutti i device)")
    void resetRevokesAllRefreshTokens() {
        registerAndVerify("frank@example.com", "frank");

        // Login → ottieni refresh token
        String refresh = given().contentType(ContentType.JSON)
                .body("{\"email\":\"frank@example.com\",\"password\":\"Password123\"}")
                .when().post("/auth/login").then().statusCode(200)
                .extract().jsonPath().getString("refreshToken");

        // Reset
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"frank@example.com\"}")
                .when().post("/auth/forgot-password").then().statusCode(204);
        String token = extractResetToken("frank@example.com");
        given().contentType(ContentType.JSON)
                .body("{\"token\":\"" + token + "\",\"password\":\"NewPassword456\"}")
                .when().post("/auth/reset-password").then().statusCode(204);

        // Il refresh token di prima non funziona più
        given().contentType(ContentType.JSON)
                .body("{\"refreshToken\":\"" + refresh + "\"}")
                .when().post("/auth/refresh")
                .then().statusCode(401);
    }

    @Test
    @DisplayName("POST /auth/forgot-password con email malformata: 400")
    void forgotPasswordInvalidEmail() {
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"not-an-email\"}")
                .when().post("/auth/forgot-password")
                .then().statusCode(400);
    }

    @Test
    @DisplayName("POST /auth/reset-password con password troppo corta: 400")
    void resetPasswordTooShort() {
        given().contentType(ContentType.JSON)
                .body("{\"token\":\"x12345678901234567890123456789012\",\"password\":\"short\"}")
                .when().post("/auth/reset-password")
                .then().statusCode(400);
    }

    // ----- helpers -----

    /** Registra utente, verifica email, ritorna lo username. */
    private void registerAndVerify(String email, String username) {
        given().contentType(ContentType.JSON)
                .body("""
                      {"email":"%s","password":"Password123","username":"%s","displayName":"%s","acceptPrivacy":true,"declareMinAge":true}
                      """.formatted(email, username, username))
                .when().post("/auth/register").then().statusCode(201);

        String evt = extractVerifyToken(email);
        given().contentType(ContentType.JSON)
                .body("{\"token\":\"" + evt + "\"}")
                .when().post("/auth/verify-email").then().statusCode(200);
        mailbox.clear();
    }

    private String extractVerifyToken(String email) {
        List<?> msgs = mailbox.getMailMessagesSentTo(email);
        if (msgs.isEmpty()) throw new AssertionError("Nessuna email per " + email);
        for (int i = msgs.size() - 1; i >= 0; i--) {
            String body = mailbox.getMailMessagesSentTo(email).get(i).getText();
            Matcher m = VERIFY_TOKEN_PATTERN.matcher(body);
            if (m.find()) return m.group(1);
        }
        throw new AssertionError("Token verify non trovato nelle mail di " + email);
    }

    private String extractResetToken(String email) {
        List<?> msgs = mailbox.getMailMessagesSentTo(email);
        if (msgs.isEmpty()) throw new AssertionError("Nessuna email per " + email);
        // L'ultima mail dovrebbe essere quella di reset
        for (int i = msgs.size() - 1; i >= 0; i--) {
            String body = mailbox.getMailMessagesSentTo(email).get(i).getText();
            Matcher m = RESET_TOKEN_PATTERN.matcher(body);
            if (m.find()) return m.group(1);
        }
        throw new AssertionError("Token reset non trovato nelle mail di " + email);
    }
}
