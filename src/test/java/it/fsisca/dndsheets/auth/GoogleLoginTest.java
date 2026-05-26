package it.fsisca.dndsheets.auth;

import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.parsing.Parser;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test {@code POST /auth/google} — login/registrazione via Google OIDC.
 * Usa {@link FakeGoogleTokenVerifier} per simulare risposte del verifier reale
 * senza dover chiamare la rete o avere chiavi private.
 */
@QuarkusTest
@DisplayName("AuthResource — POST /auth/google (login Google)")
class GoogleLoginTest {

    @Inject MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String dbName;

    @BeforeAll
    static void registerProblemJsonParser() {
        RestAssured.registerParser("application/problem+json", Parser.JSON);
    }

    @BeforeEach
    void resetState() {
        var db = mongoClient.getDatabase(dbName);
        db.getCollection("users").deleteMany(new Document());
        db.getCollection("refresh_tokens").deleteMany(new Document());
        FakeGoogleTokenVerifier.reset();
    }

    @AfterEach
    void tearDown() {
        FakeGoogleTokenVerifier.reset();
    }

    // ===================================================================
    //                      Nuovo utente — registrazione
    // ===================================================================

    @Test
    @DisplayName("nuovo utente: happy path → 200 + user creato con googleSub")
    void newUserHappyPath() {
        FakeGoogleTokenVerifier.setNext(new GoogleTokenVerifier.GoogleIdentity(
                "google-sub-123", "frank@example.com", true, "Frank S.", null));

        String accessToken = given()
                .contentType(ContentType.JSON)
                .body("""
                      {"idToken":"fake-token","acceptPrivacy":true}
                      """)
                .when().post("/auth/google")
                .then()
                .statusCode(200)
                .body("accessToken",       notNullValue())
                .body("refreshToken",      notNullValue())
                .body("user.email",        equalTo("frank@example.com"))
                .body("user.emailVerified", is(true))
                .body("user.displayName",  equalTo("Frank S."))
                .extract().path("accessToken");

        assertNotNull(accessToken);

        // Verifica lato DB: googleSub salvato, passwordHash null
        Document u = mongoClient.getDatabase(dbName).getCollection("users")
                .find(new Document("email", "frank@example.com")).first();
        assertNotNull(u);
        assertEquals("google-sub-123", u.getString("googleSub"));
        assertNull(u.getString("passwordHash"));
        assertTrue(u.getBoolean("emailVerified"));
        assertNotNull(u.getDate("privacyAcceptedAt"));
    }

    @Test
    @DisplayName("nuovo utente: username derivato dall'email (punto → underscore)")
    void newUserUsernameDerivedFromEmail() {
        FakeGoogleTokenVerifier.setNext(new GoogleTokenVerifier.GoogleIdentity(
                "google-sub-1", "marco.puntillo@hotmail.it", true, "Marco P.", null));

        given()
                .contentType(ContentType.JSON)
                .body("""
                      {"idToken":"x","acceptPrivacy":true}
                      """)
                .when().post("/auth/google")
                .then()
                .statusCode(200)
                .body("user.username", equalTo("marco_puntillo"));
    }

    @Test
    @DisplayName("nuovo utente: username con collisione → suffisso _2")
    void newUserUsernameCollision() {
        // Pre-popoliamo un utente con username gia' presente
        registerStandard("marco.puntillo@old.it", "marco_puntillo");

        FakeGoogleTokenVerifier.setNext(new GoogleTokenVerifier.GoogleIdentity(
                "google-sub-9", "marco.puntillo@hotmail.it", true, "Marco P.", null));

        given()
                .contentType(ContentType.JSON)
                .body("""
                      {"idToken":"x","acceptPrivacy":true}
                      """)
                .when().post("/auth/google")
                .then()
                .statusCode(200)
                .body("user.username", equalTo("marco_puntillo_2"));
    }

    @Test
    @DisplayName("nuovo utente: acceptPrivacy=false → 400 PRIVACY_NOT_ACCEPTED")
    void newUserMissingPrivacy() {
        FakeGoogleTokenVerifier.setNext(new GoogleTokenVerifier.GoogleIdentity(
                "google-sub-x", "nuovo@example.com", true, "Nuovo", null));

        given()
                .contentType(ContentType.JSON)
                .body("""
                      {"idToken":"x","acceptPrivacy":false}
                      """)
                .when().post("/auth/google")
                .then()
                .statusCode(400)
                .body("code", equalTo("PRIVACY_NOT_ACCEPTED"));

        // L'utente NON deve essere stato creato
        long count = mongoClient.getDatabase(dbName).getCollection("users")
                .countDocuments(new Document("email", "nuovo@example.com"));
        assertEquals(0L, count);
    }

    // ===================================================================
    //                      Login utente esistente
    // ===================================================================

    @Test
    @DisplayName("match googleSub: utente gia' loggato in passato → 200 senza nuova creazione")
    void existingUserBySubLogin() {
        // Pre-popoliamo un utente con googleSub
        var users = mongoClient.getDatabase(dbName).getCollection("users");
        Document u = new Document()
                .append("email",         "frank@example.com")
                .append("username",      "frank")
                .append("displayName",   "Frank")
                .append("emailVerified", true)
                .append("googleSub",     "google-sub-known")
                .append("tier",          "FREE")
                .append("createdAt",     new java.util.Date())
                .append("updatedAt",     new java.util.Date());
        users.insertOne(u);

        FakeGoogleTokenVerifier.setNext(new GoogleTokenVerifier.GoogleIdentity(
                "google-sub-known", "frank@example.com", true, "Frank From Google", null));

        given()
                .contentType(ContentType.JSON)
                .body("""
                      {"idToken":"x","acceptPrivacy":false}
                      """)
                .when().post("/auth/google")
                .then()
                .statusCode(200)
                // displayName non viene sovrascritto dal name di Google: rispettiamo
                // la scelta dell'utente esistente.
                .body("user.displayName", equalTo("Frank"));

        long count = users.countDocuments(new Document("email", "frank@example.com"));
        assertEquals(1L, count, "Non deve creare un nuovo utente");
    }

    // ===================================================================
    //                       Auto-link per email
    // ===================================================================

    @Test
    @DisplayName("auto-link: utente registrato via password → googleSub salvato")
    void autoLinkExistingPasswordUser() {
        // Utente registrato standard (con password + email verificata)
        registerStandard("frank@example.com", "frank");

        FakeGoogleTokenVerifier.setNext(new GoogleTokenVerifier.GoogleIdentity(
                "google-sub-new-link", "frank@example.com", true, "Frank G.", null));

        given()
                .contentType(ContentType.JSON)
                .body("""
                      {"idToken":"x","acceptPrivacy":false}
                      """)
                .when().post("/auth/google")
                .then()
                .statusCode(200)
                .body("user.email", equalTo("frank@example.com"));

        // googleSub salvato sull'utente esistente
        Document u = mongoClient.getDatabase(dbName).getCollection("users")
                .find(new Document("email", "frank@example.com")).first();
        assertNotNull(u);
        assertEquals("google-sub-new-link", u.getString("googleSub"));
    }

    @Test
    @DisplayName("auto-link: utente non verificato → emailVerified diventa true")
    void autoLinkPromotesUnverifiedEmail() {
        // Pre-popoliamo utente con emailVerified=false (es. registrato ma mai cliccato il link)
        var users = mongoClient.getDatabase(dbName).getCollection("users");
        users.insertOne(new Document()
                .append("email",         "frank@example.com")
                .append("username",      "frank")
                .append("displayName",   "Frank")
                .append("emailVerified", false)
                .append("passwordHash",  "$argon2id$dummy")
                .append("tier",          "FREE")
                .append("createdAt",     new java.util.Date())
                .append("updatedAt",     new java.util.Date()));

        FakeGoogleTokenVerifier.setNext(new GoogleTokenVerifier.GoogleIdentity(
                "google-sub", "frank@example.com", true, "Frank G.", null));

        given()
                .contentType(ContentType.JSON)
                .body("""
                      {"idToken":"x","acceptPrivacy":false}
                      """)
                .when().post("/auth/google")
                .then()
                .statusCode(200)
                .body("user.emailVerified", is(true));

        Document u = users.find(new Document("email", "frank@example.com")).first();
        assertTrue(u.getBoolean("emailVerified"));
    }

    // ===================================================================
    //                        Token invalido / errori
    // ===================================================================

    @Test
    @DisplayName("token invalido → 401 INVALID_GOOGLE_TOKEN")
    void invalidToken() {
        FakeGoogleTokenVerifier.setNextInvalid("ID token Google non valido");

        given()
                .contentType(ContentType.JSON)
                .body("""
                      {"idToken":"bogus","acceptPrivacy":true}
                      """)
                .when().post("/auth/google")
                .then()
                .statusCode(401)
                .body("code", equalTo("INVALID_GOOGLE_TOKEN"));
    }

    @Test
    @DisplayName("emailVerified=false dal token → 401 GOOGLE_EMAIL_NOT_VERIFIED")
    void googleEmailNotVerified() {
        FakeGoogleTokenVerifier.setNext(new GoogleTokenVerifier.GoogleIdentity(
                "google-sub", "unverified@example.com", false, "X", null));

        given()
                .contentType(ContentType.JSON)
                .body("""
                      {"idToken":"x","acceptPrivacy":true}
                      """)
                .when().post("/auth/google")
                .then()
                .statusCode(401)
                .body("code", equalTo("GOOGLE_EMAIL_NOT_VERIFIED"));
    }

    @Test
    @DisplayName("idToken mancante → 400 (bean validation)")
    void missingIdToken() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                      {"acceptPrivacy":true}
                      """)
                .when().post("/auth/google")
                .then()
                .statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"));
    }

    // ===================================================================
    //                              Helpers
    // ===================================================================

    /** Registra un utente standard via DB diretto, emailVerified=true. */
    private void registerStandard(String email, String username) {
        var users = mongoClient.getDatabase(dbName).getCollection("users");
        users.insertOne(new Document()
                .append("email",             email)
                .append("username",          username)
                .append("displayName",       username)
                .append("emailVerified",     true)
                .append("passwordHash",      "$argon2id$dummy")
                .append("privacyAcceptedAt", new java.util.Date())
                .append("tier",              "FREE")
                .append("createdAt",         new java.util.Date())
                .append("updatedAt",         new java.util.Date()));
    }
}
