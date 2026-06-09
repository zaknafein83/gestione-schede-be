package it.fsisca.dndsheets.payment;

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

/**
 * Test "Paddle not configured": senza env vars popolate, gli endpoint protetti
 * rispondono 503 PADDLE_NOT_CONFIGURED (o 400/401 sulla validation preliminare),
 * mentre /billing/config ritorna sempre 200 con enabled=false.
 */
@QuarkusTest
@DisplayName("Paddle — endpoint senza configurazione")
class PaddleNotConfiguredTest {

    private static final Pattern TOKEN_LINK_PATTERN = Pattern.compile("token=([A-Za-z0-9_-]+)");

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
        db.getCollection("refresh_tokens").deleteMany(new Document());
        db.getCollection("payment_events").deleteMany(new Document());
        mailbox.clear();
    }

    @Test
    @DisplayName("POST /me/billing/checkout senza JWT → 401")
    void checkoutRequiresAuth() {
        given()
                .when().post("/me/billing/checkout")
                .then().statusCode(401);
    }

    @Test
    @DisplayName("POST /me/billing/checkout con JWT ma Paddle non configurato → 503")
    void checkoutNotConfigured() {
        String access = registerAndLogin("frank@example.com", "frank");
        given()
                .header("Authorization", "Bearer " + access)
                .when().post("/me/billing/checkout")
                .then()
                .statusCode(503)
                .body("code", equalTo("PADDLE_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("POST /billing/webhook senza header Paddle-Signature → 400")
    void webhookMissingSignature() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"event_id\":\"evt_x\",\"event_type\":\"transaction.completed\"}")
                .when().post("/billing/webhook")
                .then().statusCode(400);
    }

    @Test
    @DisplayName("POST /billing/webhook con signature dummy ma Paddle non configurato → 503")
    void webhookNotConfigured() {
        given()
                .header("Paddle-Signature", "ts=1;h1=dummy")
                .contentType(ContentType.JSON)
                .body("{\"event_id\":\"evt_x\",\"event_type\":\"transaction.completed\"}")
                .when().post("/billing/webhook")
                .then()
                .statusCode(503)
                .body("code", equalTo("PADDLE_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("GET /billing/config senza configurazione → 200 enabled=false")
    void configDisabledWhenNotConfigured() {
        given()
                .when().get("/billing/config")
                .then()
                .statusCode(200)
                .body("enabled", equalTo(false));
    }

    // ----- helpers -----

    private String registerAndLogin(String email, String username) {
        given().contentType(ContentType.JSON)
                .body("""
                      {"email":"%s","password":"Password123","username":"%s","displayName":"%s","acceptPrivacy":true,"declareMinAge":true}
                      """.formatted(email, username, username))
                .when().post("/auth/register").then().statusCode(201);
        String evt = extractTokenFromMailboxFor(email);
        given().contentType(ContentType.JSON)
                .body("{\"token\":\"" + evt + "\"}")
                .when().post("/auth/verify-email").then().statusCode(200);
        return given().contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"password\":\"Password123\"}")
                .when().post("/auth/login").then().statusCode(200)
                .extract().jsonPath().getString("accessToken");
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
