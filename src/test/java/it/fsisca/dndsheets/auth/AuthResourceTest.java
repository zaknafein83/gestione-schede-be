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

import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test di integrazione per {@link AuthResource} — feature F1
 * (registrazione + verifica email).
 * <p>
 * Usa Mongo effimero (Dev Services), MockMailbox per intercettare le email,
 * e parte da DB pulito davanti a ogni test.
 */
@QuarkusTest
@DisplayName("AuthResource — F1 register + verify-email")
class AuthResourceTest {

    private static final Pattern TOKEN_LINK_PATTERN =
            Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Inject MongoClient mongoClient;
    @Inject MockMailbox mailbox;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String dbName;

    @BeforeAll
    static void registerProblemJsonParser() {
        // RestAssured non parsa "application/problem+json" out-of-the-box: lo trattiamo come JSON.
        RestAssured.registerParser("application/problem+json", Parser.JSON);
    }

    @BeforeEach
    void resetState() {
        var db = mongoClient.getDatabase(dbName);
        db.getCollection("users").deleteMany(new Document());
        db.getCollection("email_verifications").deleteMany(new Document());
        mailbox.clear();
    }

    // ===========================================================
    //                       POST /auth/register
    // ===========================================================

    @Test
    @DisplayName("register: happy path → 201 + utente non verificato + email inviata")
    void registerHappyPath() {
        given()
                .contentType(ContentType.JSON)
                .body(validPayload("frank@example.com", "frank", "Frank S.", "Password123"))
                .when().post("/auth/register")
                .then()
                .statusCode(201)
                .body("id",            notNullValue())
                .body("email",         equalTo("frank@example.com"))
                .body("username",      equalTo("frank"))
                .body("displayName",   equalTo("Frank S."))
                .body("emailVerified", is(false))
                .body("createdAt",     notNullValue());

        // E' stata inviata esattamente una mail al destinatario corretto
        List<?> sent = mailbox.getMailMessagesSentTo("frank@example.com");
        assertEquals(1, sent.size(), "Una sola mail di verifica attesa");
    }

    @Test
    @DisplayName("register: email viene normalizzata in lowercase")
    void registerNormalizesEmail() {
        // Nota: il trim degli spazi avverrebbe lato server, ma @Email rifiuta gli spazi
        // a monte; il client e' atteso fare il trim. Qui verifichiamo il lowercase.
        given()
                .contentType(ContentType.JSON)
                .body(validPayload("Frank@Example.COM", "frank", "Frank", "Password123"))
                .when().post("/auth/register")
                .then()
                .statusCode(201)
                .body("email", equalTo("frank@example.com"));
    }

    @Test
    @DisplayName("register: stessa email (anche case diverso) → 409 EMAIL_ALREADY_USED")
    void registerDuplicateEmail() {
        registerOk("frank@example.com", "frank");

        given()
                .contentType(ContentType.JSON)
                .body(validPayload("FRANK@example.com", "anotherUser", "Other", "Password123"))
                .when().post("/auth/register")
                .then()
                .statusCode(409)
                .body("code",   equalTo("EMAIL_ALREADY_USED"))
                .body("status", equalTo(409));
    }

    @Test
    @DisplayName("register: stesso username (case-sensitive) → 409 USERNAME_ALREADY_USED")
    void registerDuplicateUsername() {
        registerOk("frank@example.com", "frank");

        given()
                .contentType(ContentType.JSON)
                .body(validPayload("other@example.com", "frank", "Other", "Password123"))
                .when().post("/auth/register")
                .then()
                .statusCode(409)
                .body("code", equalTo("USERNAME_ALREADY_USED"));
    }

    @Test
    @DisplayName("register: email malformata → 400")
    void registerBadEmail() {
        given()
                .contentType(ContentType.JSON)
                .body(validPayload("not-an-email", "frank", "Frank", "Password123"))
                .when().post("/auth/register")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("register: password senza maiuscola → 400")
    void registerPasswordWithoutUppercase() {
        given()
                .contentType(ContentType.JSON)
                .body(validPayload("frank@example.com", "frank", "Frank", "password123"))
                .when().post("/auth/register")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("register: password senza numero → 400")
    void registerPasswordWithoutDigit() {
        given()
                .contentType(ContentType.JSON)
                .body(validPayload("frank@example.com", "frank", "Frank", "PasswordOnly"))
                .when().post("/auth/register")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("register: acceptPrivacy=false → 400 (proof of consent GDPR)")
    void registerWithoutPrivacyConsent() {
        // Payload identico al valido, ma con acceptPrivacy=false
        String body = """
                {
                  "email": "frank@example.com",
                  "password": "Password123",
                  "username": "frank",
                  "displayName": "Frank S.",
                  "acceptPrivacy": false
                }
                """;
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/auth/register")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("register: acceptPrivacy omesso (default false) → 400")
    void registerWithoutPrivacyField() {
        // Senza il campo acceptPrivacy il primitive boolean assume default false
        String body = """
                {
                  "email": "frank@example.com",
                  "password": "Password123",
                  "username": "frank",
                  "displayName": "Frank S."
                }
                """;
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/auth/register")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("register: acceptPrivacy=true salva privacyAcceptedAt sull'utente")
    void registerSavesPrivacyTimestamp() {
        given()
                .contentType(ContentType.JSON)
                .body(validPayload("frank@example.com", "frank", "Frank S.", "Password123"))
                .when().post("/auth/register")
                .then().statusCode(201);

        // Verifica direttamente su Mongo che il timestamp sia stato persistito
        Document u = mongoClient.getDatabase(dbName)
                .getCollection("users")
                .find(new Document("email", "frank@example.com"))
                .first();
        assertNotNull(u, "Utente trovato");
        assertNotNull(u.get("privacyAcceptedAt"), "privacyAcceptedAt deve essere valorizzato");
    }

    @Test
    @DisplayName("register: password troppo corta → 400")
    void registerPasswordTooShort() {
        given()
                .contentType(ContentType.JSON)
                .body(validPayload("frank@example.com", "frank", "Frank", "Pwd123"))
                .when().post("/auth/register")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("register: username con caratteri non ammessi → 400")
    void registerBadUsername() {
        given()
                .contentType(ContentType.JSON)
                .body(validPayload("frank@example.com", "frank!", "Frank", "Password123"))
                .when().post("/auth/register")
                .then()
                .statusCode(400);
    }

    // ===========================================================
    //                    POST /auth/verify-email
    // ===========================================================

    @Test
    @DisplayName("verify-email: happy path → 200 + emailVerified=true + token consumato")
    void verifyEmailHappyPath() {
        registerOk("frank@example.com", "frank");
        String token = extractTokenFromMailboxFor("frank@example.com");
        assertNotNull(token);

        given()
                .contentType(ContentType.JSON)
                .body("{\"token\":\"" + token + "\"}")
                .when().post("/auth/verify-email")
                .then()
                .statusCode(200)
                .body("email",         equalTo("frank@example.com"))
                .body("emailVerified", is(true));

        // Riutilizzo dello stesso token → ora rifiutato
        given()
                .contentType(ContentType.JSON)
                .body("{\"token\":\"" + token + "\"}")
                .when().post("/auth/verify-email")
                .then()
                .statusCode(400)
                .body("code", equalTo("EXPIRED_OR_USED_TOKEN"));
    }

    @Test
    @DisplayName("verify-email: token sconosciuto → 400 INVALID_TOKEN")
    void verifyEmailUnknownToken() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"token\":\"un-token-che-non-esiste\"}")
                .when().post("/auth/verify-email")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("verify-email: token scaduto → 400 EXPIRED_OR_USED_TOKEN")
    void verifyEmailExpiredToken() {
        registerOk("frank@example.com", "frank");
        String token = extractTokenFromMailboxFor("frank@example.com");

        // Forza la scadenza nel passato modificando il documento direttamente
        var coll = mongoClient.getDatabase(dbName).getCollection("email_verifications");
        coll.updateMany(
                new Document(),
                new Document("$set", new Document("expiresAt", Instant.now().minusSeconds(60))));

        given()
                .contentType(ContentType.JSON)
                .body("{\"token\":\"" + token + "\"}")
                .when().post("/auth/verify-email")
                .then()
                .statusCode(400)
                .body("code", equalTo("EXPIRED_OR_USED_TOKEN"));
    }

    @Test
    @DisplayName("verify-email: token vuoto → 400 (validation)")
    void verifyEmailBlankToken() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"token\":\"\"}")
                .when().post("/auth/verify-email")
                .then()
                .statusCode(400);
    }

    // ===========================================================
    //                          helpers
    // ===========================================================

    private static String validPayload(String email, String username, String displayName, String password) {
        return """
                {
                  "email": "%s",
                  "password": "%s",
                  "username": "%s",
                  "displayName": "%s",
                  "acceptPrivacy": true
                }
                """.formatted(email, password, username, displayName);
    }

    private void registerOk(String email, String username) {
        given()
                .contentType(ContentType.JSON)
                .body(validPayload(email, username, "Display Name", "Password123"))
                .when().post("/auth/register")
                .then()
                .statusCode(201);
    }

    /** Cerca il primo token nell'ultima email mandata a {@code email}. */
    private String extractTokenFromMailboxFor(String email) {
        var messages = mailbox.getMailMessagesSentTo(email);
        if (messages.isEmpty()) {
            throw new AssertionError("Nessuna email in mailbox per " + email);
        }
        String body = messages.get(messages.size() - 1).getText();
        Matcher m = TOKEN_LINK_PATTERN.matcher(body);
        if (!m.find()) {
            throw new AssertionError("Token non trovato nel body email: " + body);
        }
        return m.group(1);
    }
}
