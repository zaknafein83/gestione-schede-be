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
 * Test "Stripe not configured": senza env vars popolate, i due endpoint
 * devono rispondere 503 STRIPE_NOT_CONFIGURED, oppure 400 sulla validation
 * preliminare del payload/firma. La config reale verra' testata
 * solo manualmente in TEST mode con le sk_test di Stripe.
 */
@QuarkusTest
@DisplayName("Stripe — endpoint senza configurazione")
class StripeNotConfiguredTest {

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
    @DisplayName("POST /me/stripe/checkout-session senza JWT → 401")
    void checkoutSessionRequiresAuth() {
        given()
                .when().post("/me/stripe/checkout-session")
                .then().statusCode(401);
    }

    @Test
    @DisplayName("POST /me/stripe/checkout-session con JWT ma Stripe non configurato → 503")
    void checkoutSessionNotConfigured() {
        String access = registerAndLogin("frank@example.com", "frank");
        given()
                .header("Authorization", "Bearer " + access)
                .when().post("/me/stripe/checkout-session")
                .then()
                .statusCode(503)
                .body("code", equalTo("STRIPE_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("POST /stripe/webhook senza header Stripe-Signature → 400")
    void webhookMissingSignature() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"id\":\"evt_x\",\"type\":\"checkout.session.completed\"}")
                .when().post("/stripe/webhook")
                .then().statusCode(400);
    }

    @Test
    @DisplayName("POST /stripe/webhook con signature dummy ma Stripe non configurato → 503")
    void webhookNotConfigured() {
        given()
                .header("Stripe-Signature", "t=1,v1=dummy")
                .contentType(ContentType.JSON)
                .body("{\"id\":\"evt_x\",\"type\":\"checkout.session.completed\"}")
                .when().post("/stripe/webhook")
                .then()
                .statusCode(503)
                .body("code", equalTo("STRIPE_NOT_CONFIGURED"));
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
