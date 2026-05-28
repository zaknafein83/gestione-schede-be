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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Test della feature F2: login, refresh token rotation, logout, endpoint /me protetto.
 */
@QuarkusTest
@DisplayName("Feature F2 — login + refresh + logout + /me")
class LoginFlowTest {

    private static final Pattern TOKEN_LINK_PATTERN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Inject MongoClient mongoClient;
    @Inject MockMailbox mailbox;

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
        db.getCollection("email_verifications").deleteMany(new Document());
        db.getCollection("refresh_tokens").deleteMany(new Document());
        mailbox.clear();
    }

    // =====================================================================
    //                              login
    // =====================================================================

    @Test
    @DisplayName("login: utente verificato → 200 con access+refresh+user")
    void loginHappyPath() {
        registerAndVerify("frank@example.com", "frank", "Password123");

        given()
                .contentType(ContentType.JSON)
                .body(loginBody("frank@example.com", "Password123"))
                .when().post("/auth/login")
                .then()
                .statusCode(200)
                .body("accessToken",  notNullValue())
                .body("refreshToken", notNullValue())
                .body("user.email",         equalTo("frank@example.com"))
                .body("user.username",      equalTo("frank"))
                .body("user.emailVerified", is(true));
    }

    @Test
    @DisplayName("login: email non verificata → 403 EMAIL_NOT_VERIFIED")
    void loginEmailNotVerified() {
        registerOnly("frank@example.com", "frank", "Password123");

        given()
                .contentType(ContentType.JSON)
                .body(loginBody("frank@example.com", "Password123"))
                .when().post("/auth/login")
                .then()
                .statusCode(403)
                .body("code", equalTo("EMAIL_NOT_VERIFIED"));
    }

    @Test
    @DisplayName("login: password sbagliata → 401 INVALID_CREDENTIALS")
    void loginWrongPassword() {
        registerAndVerify("frank@example.com", "frank", "Password123");

        given()
                .contentType(ContentType.JSON)
                .body(loginBody("frank@example.com", "Wrong12345"))
                .when().post("/auth/login")
                .then()
                .statusCode(401)
                .body("code", equalTo("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("login: email inesistente → 401 INVALID_CREDENTIALS (no user enumeration)")
    void loginUnknownEmail() {
        given()
                .contentType(ContentType.JSON)
                .body(loginBody("nope@example.com", "Whatever123"))
                .when().post("/auth/login")
                .then()
                .statusCode(401)
                .body("code", equalTo("INVALID_CREDENTIALS"));
    }

    // =====================================================================
    //                              refresh
    // =====================================================================

    @Test
    @DisplayName("refresh: token valido → 200 nuova coppia, il vecchio refresh viene revocato")
    void refreshHappyAndRotation() {
        registerAndVerify("frank@example.com", "frank", "Password123");
        var first = login("frank@example.com", "Password123");

        var second = given()
                .contentType(ContentType.JSON)
                .body("{\"refreshToken\":\"" + first.refreshToken + "\"}")
                .when().post("/auth/refresh")
                .then()
                .statusCode(200)
                .extract().jsonPath();

        String newAccess  = second.getString("accessToken");
        String newRefresh = second.getString("refreshToken");

        // rotation: il vecchio refresh non funziona più
        given()
                .contentType(ContentType.JSON)
                .body("{\"refreshToken\":\"" + first.refreshToken + "\"}")
                .when().post("/auth/refresh")
                .then()
                .statusCode(401)
                .body("code", equalTo("EXPIRED_OR_REVOKED_REFRESH_TOKEN"));

        // il nuovo refresh funziona
        assertNotEquals(first.refreshToken, newRefresh, "il refresh deve essere ruotato");
        assertNotEquals(first.accessToken,  newAccess,  "l'access deve essere nuovo");
    }

    @Test
    @DisplayName("refresh: token sconosciuto → 401 INVALID_REFRESH_TOKEN")
    void refreshUnknown() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"refreshToken\":\"un-token-inesistente\"}")
                .when().post("/auth/refresh")
                .then()
                .statusCode(401)
                .body("code", equalTo("INVALID_REFRESH_TOKEN"));
    }

    @Test
    @DisplayName("refresh: token scaduto → 401 EXPIRED_OR_REVOKED")
    void refreshExpired() {
        registerAndVerify("frank@example.com", "frank", "Password123");
        var first = login("frank@example.com", "Password123");

        // forzo expiresAt nel passato
        mongoClient.getDatabase(dbName).getCollection("refresh_tokens")
                .updateMany(new Document(),
                        new Document("$set", new Document("expiresAt", Instant.now().minusSeconds(60))));

        given()
                .contentType(ContentType.JSON)
                .body("{\"refreshToken\":\"" + first.refreshToken + "\"}")
                .when().post("/auth/refresh")
                .then()
                .statusCode(401)
                .body("code", equalTo("EXPIRED_OR_REVOKED_REFRESH_TOKEN"));
    }

    // =====================================================================
    //                              logout
    // =====================================================================

    @Test
    @DisplayName("logout: revoca il refresh — successivo refresh fallisce")
    void logout() {
        registerAndVerify("frank@example.com", "frank", "Password123");
        var first = login("frank@example.com", "Password123");

        given()
                .contentType(ContentType.JSON)
                .body("{\"refreshToken\":\"" + first.refreshToken + "\"}")
                .when().post("/auth/logout")
                .then()
                .statusCode(204);

        given()
                .contentType(ContentType.JSON)
                .body("{\"refreshToken\":\"" + first.refreshToken + "\"}")
                .when().post("/auth/refresh")
                .then()
                .statusCode(401)
                .body("code", equalTo("EXPIRED_OR_REVOKED_REFRESH_TOKEN"));
    }

    @Test
    @DisplayName("logout: token inesistente → 204 comunque (idempotente)")
    void logoutUnknownTokenIsIdempotent() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"refreshToken\":\"non-esiste\"}")
                .when().post("/auth/logout")
                .then()
                .statusCode(204);
    }

    // =====================================================================
    //                               /me
    // =====================================================================

    @Test
    @DisplayName("GET /me senza token → 401")
    void meUnauthenticated() {
        given().when().get("/me").then().statusCode(401);
    }

    @Test
    @DisplayName("GET /me con access valido → 200 con dati utente")
    void meAuthenticated() {
        registerAndVerify("frank@example.com", "frank", "Password123");
        var first = login("frank@example.com", "Password123");

        given()
                .header("Authorization", "Bearer " + first.accessToken)
                .when().get("/me")
                .then()
                .statusCode(200)
                .body("email",    equalTo("frank@example.com"))
                .body("username", equalTo("frank"));
    }

    @Test
    @DisplayName("GET /me con token malformato → 401")
    void meBadToken() {
        given()
                .header("Authorization", "Bearer not-a-jwt")
                .when().get("/me")
                .then()
                .statusCode(401);
    }

    // =====================================================================
    //                              helpers
    // =====================================================================

    private record Tokens(String accessToken, String refreshToken) {}

    private void registerOnly(String email, String username, String password) {
        given()
                .contentType(ContentType.JSON)
                .body("""
                      {"email":"%s","password":"%s","username":"%s","displayName":"%s","acceptPrivacy":true,"declareMinAge":true}
                      """.formatted(email, password, username, username))
                .when().post("/auth/register")
                .then().statusCode(201);
    }

    private void registerAndVerify(String email, String username, String password) {
        registerOnly(email, username, password);
        String token = extractTokenFromMailboxFor(email);
        given()
                .contentType(ContentType.JSON)
                .body("{\"token\":\"" + token + "\"}")
                .when().post("/auth/verify-email")
                .then().statusCode(200);
    }

    private Tokens login(String email, String password) {
        var path = given()
                .contentType(ContentType.JSON)
                .body(loginBody(email, password))
                .when().post("/auth/login")
                .then().statusCode(200).extract().jsonPath();
        return new Tokens(path.getString("accessToken"), path.getString("refreshToken"));
    }

    private static String loginBody(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }

    private String extractTokenFromMailboxFor(String email) {
        List<?> msgs = mailbox.getMailMessagesSentTo(email);
        if (msgs.isEmpty()) throw new AssertionError("Nessuna email per " + email);
        String body = mailbox.getMailMessagesSentTo(email).get(msgs.size() - 1).getText();
        Matcher m = TOKEN_LINK_PATTERN.matcher(body);
        if (!m.find()) throw new AssertionError("Token non trovato: " + body);
        return m.group(1);
    }
}
