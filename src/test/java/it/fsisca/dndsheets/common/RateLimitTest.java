package it.fsisca.dndsheets.common;

import com.mongodb.client.MongoClient;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.parsing.Parser;
import it.fsisca.dndsheets.common.ratelimit.RateLimitService;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Rate limit su /auth/login (2/min) e /auth/forgot-password (1/min) con
 * profilo dedicato. Verifica burst, 429+Retry-After, separazione per IP.
 */
@QuarkusTest
@TestProfile(RateLimitTest.LowLimitProfile.class)
@DisplayName("Rate limit /auth/*")
class RateLimitTest {

    public static class LowLimitProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "app.rate-limit.login.max-per-minute", "2",
                    "app.rate-limit.forgot-password.max-per-minute", "1");
        }
    }

    private static final Pattern TOKEN_LINK_PATTERN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Inject MongoClient        mongoClient;
    @Inject MockMailbox        mailbox;
    @Inject RateLimitService   rateLimitService;

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
        db.getCollection("refresh_tokens").deleteMany(new Document());
        db.getCollection("password_reset_tokens").deleteMany(new Document());
        mailbox.clear();
        rateLimitService.resetAll();
    }

    // ==================================================================
    //                         /auth/login
    // ==================================================================

    @Test
    @DisplayName("/auth/login: 2 ok + 1 → 429 con Retry-After")
    void loginRateLimitedAfterBurst() {
        registerAndVerify("frank@example.com", "frank");

        // Le prime 2 vanno (anche con credenziali errate consumano comunque
        // il token — vogliamo bloccare il brute force, non solo i success).
        attemptLogin("1.2.3.4", "frank@example.com", "wrong").then().statusCode(401);
        attemptLogin("1.2.3.4", "frank@example.com", "wrong").then().statusCode(401);

        // 3a: 429
        attemptLogin("1.2.3.4", "frank@example.com", "wrong")
                .then()
                .statusCode(429)
                .header("Retry-After", notNullValue())
                .body("code",   equalTo("RATE_LIMITED"))
                .body("status", is(429));
    }

    @Test
    @DisplayName("IP diversi → bucket separati (X-Forwarded-For)")
    void differentIpsHaveSeparateBuckets() {
        registerAndVerify("frank@example.com", "frank");

        // IP A satura
        attemptLogin("9.9.9.9", "frank@example.com", "wrong").then().statusCode(401);
        attemptLogin("9.9.9.9", "frank@example.com", "wrong").then().statusCode(401);
        attemptLogin("9.9.9.9", "frank@example.com", "wrong").then().statusCode(429);

        // IP B parte da capo
        attemptLogin("8.8.8.8", "frank@example.com", "wrong").then().statusCode(401);
        attemptLogin("8.8.8.8", "frank@example.com", "wrong").then().statusCode(401);
        attemptLogin("8.8.8.8", "frank@example.com", "wrong").then().statusCode(429);
    }

    // ==================================================================
    //                     /auth/forgot-password
    // ==================================================================

    @Test
    @DisplayName("/auth/forgot-password: 1 ok + 1 → 429 (limite=1/min)")
    void forgotPasswordRateLimited() {
        attemptForgot("5.5.5.5", "frank@example.com").then().statusCode(204);

        attemptForgot("5.5.5.5", "frank@example.com")
                .then()
                .statusCode(429)
                .header("Retry-After", notNullValue())
                .body("code", equalTo("RATE_LIMITED"));
    }

    @Test
    @DisplayName("login e forgot-password hanno bucket separati")
    void loginAndForgotAreSeparateBuckets() {
        registerAndVerify("frank@example.com", "frank");
        // Bucket login: 2 ok, 3a 429
        attemptLogin("7.7.7.7", "frank@example.com", "wrong").then().statusCode(401);
        attemptLogin("7.7.7.7", "frank@example.com", "wrong").then().statusCode(401);
        attemptLogin("7.7.7.7", "frank@example.com", "wrong").then().statusCode(429);

        // Forgot: bucket separato, prima richiesta passa
        attemptForgot("7.7.7.7", "frank@example.com").then().statusCode(204);
    }

    // ==================================================================
    //                          helpers
    // ==================================================================

    private io.restassured.response.Response attemptLogin(String ip, String email, String password) {
        return given()
                .header("X-Forwarded-For", ip)
                .contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .when().post("/auth/login");
    }

    private io.restassured.response.Response attemptForgot(String ip, String email) {
        return given()
                .header("X-Forwarded-For", ip)
                .contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\"}")
                .when().post("/auth/forgot-password");
    }

    private void registerAndVerify(String email, String username) {
        given()
                .contentType(ContentType.JSON)
                .body("""
                      {"email":"%s","password":"Password123","username":"%s","displayName":"%s"}
                      """.formatted(email, username, username))
                .when().post("/auth/register")
                .then().statusCode(201);

        String evt = extractTokenFromMailboxFor(email);
        given()
                .contentType(ContentType.JSON)
                .body("{\"token\":\"" + evt + "\"}")
                .when().post("/auth/verify-email")
                .then().statusCode(200);
    }

    private String extractTokenFromMailboxFor(String email) {
        var msgs = mailbox.getMailMessagesSentTo(email);
        if (msgs.isEmpty()) throw new AssertionError("Nessuna email per " + email);
        String body = msgs.get(msgs.size() - 1).getText();
        Matcher m = TOKEN_LINK_PATTERN.matcher(body);
        if (!m.find()) throw new AssertionError("Token non trovato: " + body);
        return m.group(1);
    }
}
