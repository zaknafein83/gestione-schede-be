package it.fsisca.dndsheets.payment;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.mongodb.client.model.Filters.eq;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Test webhook Paddle con configurazione attiva (secret noto via {@link Profile}).
 * Copre: firma valida → GRANT_PREMIUM, dedup su event_id ripetuto, refund →
 * DOWNGRADE_FREE, firma non valida → 401.
 */
@QuarkusTest
@TestProfile(PaddleWebhookTest.Profile.class)
@DisplayName("Paddle — webhook firmato (grant / dedup / refund)")
class PaddleWebhookTest {

    static final String SECRET = "pdl_ntfset_testsecret_0123456789";

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "app.paddle.client-token",   "test_token_dummy",
                    "app.paddle.price-id",       "pri_test_dummy",
                    "app.paddle.webhook-secret", SECRET,
                    "app.paddle.environment",    "sandbox");
        }
    }

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
        MongoDatabase db = mongoClient.getDatabase(dbName);
        db.getCollection("users").deleteMany(new Document());
        db.getCollection("email_verifications").deleteMany(new Document());
        db.getCollection("refresh_tokens").deleteMany(new Document());
        db.getCollection("payment_events").deleteMany(new Document());
        mailbox.clear();
    }

    @Test
    @DisplayName("transaction.completed firmato → utente Premium + 1 evento GRANT, dedup su replay")
    void grantAndDedup() {
        registerAndVerify("buyer@example.com", "buyer");
        String uid = userIdHex("buyer@example.com");

        String body = txnCompleted("evt_1", "txn_1", uid);
        postSigned(body).then().statusCode(200);

        assertEquals("PREMIUM", userField("buyer@example.com", "tier"));
        assertEquals("PADDLE",  userField("buyer@example.com", "premiumSource"));
        assertEquals(1, grantCount());

        // Replay dello stesso event_id → dedup, nessun nuovo evento.
        postSigned(body).then().statusCode(200);
        assertEquals(1, grantCount());
        assertEquals("PREMIUM", userField("buyer@example.com", "tier"));
    }

    @Test
    @DisplayName("adjustment.created refund → downgrade a FREE")
    void refundDowngrades() {
        registerAndVerify("ref@example.com", "refuser");
        String uid = userIdHex("ref@example.com");

        postSigned(txnCompleted("evt_g", "txn_g", uid)).then().statusCode(200);
        assertEquals("PREMIUM", userField("ref@example.com", "tier"));

        String refund = """
                {"event_id":"evt_r","event_type":"adjustment.created",
                 "occurred_at":"2026-06-09T10:05:00Z",
                 "data":{"id":"adj_1","action":"refund","status":"approved",
                         "transaction_id":"txn_g","customer_id":"ctm_1"}}""";
        postSigned(refund).then().statusCode(200);

        assertEquals("FREE", userField("ref@example.com", "tier"));
        assertNull(userField("ref@example.com", "premiumSource"));
    }

    @Test
    @DisplayName("firma non valida → 401")
    void invalidSignature() {
        registerAndVerify("bad@example.com", "baduser");
        String body = txnCompleted("evt_bad", "txn_bad", userIdHex("bad@example.com"));
        long ts = Instant.now().getEpochSecond();
        given()
                .header("Paddle-Signature", "ts=" + ts + ";h1=deadbeef")
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/billing/webhook")
                .then().statusCode(401);
        // Nessun grant applicato.
        assertEquals("FREE", userField("bad@example.com", "tier"));
    }

    // ----- helpers -----

    private static String txnCompleted(String eventId, String txnId, String uid) {
        return """
               {"event_id":"%s","event_type":"transaction.completed",
                "occurred_at":"2026-06-09T10:00:00Z",
                "data":{"id":"%s","status":"completed","customer_id":"ctm_1",
                        "currency_code":"EUR","custom_data":{"user_id":"%s"},
                        "details":{"totals":{"grand_total":"499"}}}}"""
                .formatted(eventId, txnId, uid);
    }

    private io.restassured.response.Response postSigned(String body) {
        long ts = Instant.now().getEpochSecond();
        String h1 = hmacHex(SECRET, ts + ":" + body);
        return given()
                .header("Paddle-Signature", "ts=" + ts + ";h1=" + h1)
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/billing/webhook");
    }

    private static String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                                 .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long grantCount() {
        return mongoClient.getDatabase(dbName).getCollection("payment_events")
                .countDocuments(eq("outcome", "GRANT_PREMIUM"));
    }

    private String userField(String email, String field) {
        Document u = mongoClient.getDatabase(dbName).getCollection("users")
                .find(eq("email", email)).first();
        if (u == null) throw new AssertionError("Utente non trovato: " + email);
        return u.getString(field);
    }

    private String userIdHex(String email) {
        Document u = mongoClient.getDatabase(dbName).getCollection("users")
                .find(eq("email", email)).first();
        if (u == null) throw new AssertionError("Utente non trovato: " + email);
        return u.getObjectId("_id").toHexString();
    }

    private void registerAndVerify(String email, String username) {
        given().contentType(ContentType.JSON)
                .body("""
                      {"email":"%s","password":"Password123","username":"%s","displayName":"%s","acceptPrivacy":true,"declareMinAge":true}
                      """.formatted(email, username, username))
                .when().post("/auth/register").then().statusCode(201);
        String evt = extractTokenFromMailboxFor(email);
        given().contentType(ContentType.JSON)
                .body("{\"token\":\"" + evt + "\"}")
                .when().post("/auth/verify-email").then().statusCode(200);
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
