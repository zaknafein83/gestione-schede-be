package it.fsisca.dndsheets.character;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
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

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Verifica il limite scheda per il tier FREE. Riporta il limite a 1 (sotto il
 * default %test=999) tramite TestProfile, così i test storici non si rompono.
 */
@QuarkusTest
@TestProfile(CharacterTierLimitTest.LowLimitProfile.class)
@DisplayName("Tier — limite schede per utente FREE")
class CharacterTierLimitTest {

    public static class LowLimitProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("app.free-tier.max-characters", "1");
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
        var db = mongoClient.getDatabase(dbName);
        db.getCollection("users").deleteMany(new Document());
        db.getCollection("email_verifications").deleteMany(new Document());
        db.getCollection("refresh_tokens").deleteMany(new Document());
        db.getCollection("characters").deleteMany(new Document());
        mailbox.clear();
    }

    // ==================================================================
    //                            FREE TIER
    // ==================================================================

    @Test
    @DisplayName("Utente FREE: 1 scheda ok, alla 2a → 402 + TIER_LIMIT_REACHED")
    void freeUserHitsLimitOnSecondCreate() {
        String access = registerAndLogin("frank@example.com", "frank");

        createCharacter(access, "{\"name\":\"PG 1\"}");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"name\":\"PG 2\"}")
                .when().post("/characters")
                .then()
                .statusCode(402)
                .body("code",   equalTo("TIER_LIMIT_REACHED"))
                .body("status", is(402));

        // La lista resta a 1
        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/characters")
                .then().statusCode(200).body("$", org.hamcrest.Matchers.hasSize(1));
    }

    @Test
    @DisplayName("Utente FREE: duplicate al limite → 402, l'originale resta intoccato")
    void freeUserHitsLimitOnDuplicate() {
        String access = registerAndLogin("frank@example.com", "frank");

        String idA = createCharacter(access, "{\"name\":\"PG A\"}");

        given()
                .header("Authorization", "Bearer " + access)
                .when().post("/characters/" + idA + "/duplicate")
                .then()
                .statusCode(402)
                .body("code", equalTo("TIER_LIMIT_REACHED"));

        // Originale ancora presente, lista = 1
        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/characters")
                .then().statusCode(200).body("$", org.hamcrest.Matchers.hasSize(1));
    }

    @Test
    @DisplayName("Limite e' per-utente: l'utente B non viene bloccato perche' A e' a limite")
    void limitIsPerUser() {
        String accessA = registerAndLogin("a@example.com", "userA");
        String accessB = registerAndLogin("b@example.com", "userB");

        createCharacter(accessA, "{\"name\":\"A1\"}");

        // B puo' ancora creare la sua prima scheda
        createCharacter(accessB, "{\"name\":\"B1\"}");

        // A no, ha gia' raggiunto il limite
        given()
                .header("Authorization", "Bearer " + accessA)
                .contentType(ContentType.JSON)
                .body("{\"name\":\"A2\"}")
                .when().post("/characters")
                .then().statusCode(402);
    }

    // ==================================================================
    //                          PREMIUM TIER
    // ==================================================================

    @Test
    @DisplayName("Utente PREMIUM: nessun limite, puo' creare molte schede")
    void premiumUserNoLimit() {
        String access = registerAndLogin("frank@example.com", "frank");
        promoteToPremium("frank@example.com");

        // Forza relogin per riprendere lo stato premium (anche se in realta'
        // basta il check server-side che usa l'entity letta dal DB).
        for (int i = 1; i <= 5; i++) {
            createCharacter(access, "{\"name\":\"PG " + i + "\"}");
        }

        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/characters")
                .then().statusCode(200).body("$", org.hamcrest.Matchers.hasSize(5));
    }

    @Test
    @DisplayName("Utente ADMIN: nessun limite, anche se tier=FREE")
    void adminUserNoLimit() {
        String access = registerAndLogin("admin@example.com", "admin");
        promoteToAdmin("admin@example.com");

        for (int i = 1; i <= 4; i++) {
            createCharacter(access, "{\"name\":\"PG " + i + "\"}");
        }
    }

    // ==================================================================
    //                          helpers
    // ==================================================================

    private void promoteToPremium(String email) {
        mongoClient.getDatabase(dbName).getCollection("users").updateOne(
                Filters.eq("email", email),
                Updates.combine(
                        Updates.set("tier", "PREMIUM"),
                        Updates.set("premiumSince", java.time.Instant.now()),
                        Updates.set("premiumSource", "ADMIN_GRANT")));
    }

    private void promoteToAdmin(String email) {
        mongoClient.getDatabase(dbName).getCollection("users").updateOne(
                Filters.eq("email", email),
                Updates.set("roles", List.of("ADMIN")));
    }

    private String createCharacter(String access, String jsonBody) {
        return given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body(jsonBody)
                .when().post("/characters")
                .then().statusCode(201)
                .extract().jsonPath().getString("id");
    }

    private String registerAndLogin(String email, String username) {
        given()
                .contentType(ContentType.JSON)
                .body("""
                      {"email":"%s","password":"Password123","username":"%s","displayName":"%s","acceptPrivacy":true,"declareMinAge":true}
                      """.formatted(email, username, username))
                .when().post("/auth/register")
                .then().statusCode(201);

        String evt = extractTokenFromMailboxFor(email);
        given()
                .contentType(ContentType.JSON)
                .body("{\"token\":\"" + evt + "\"}")
                .when().post("/auth/verify-email")
                .then().statusCode(200);

        return given()
                .contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"password\":\"Password123\"}")
                .when().post("/auth/login")
                .then().statusCode(200)
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
