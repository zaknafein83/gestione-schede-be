package it.fsisca.dndsheets.dice;

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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Test feature M3-5 — cronologia tiri dadi server-side.
 */
@QuarkusTest
@DisplayName("Feature M3-5 — cronologia tiri")
class DiceRollResourceTest {

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
        db.getCollection("dice_rolls").deleteMany(new Document());
        mailbox.clear();
    }

    @Test
    @DisplayName("POST /dice-rolls: happy path → 201 + id")
    void createOk() {
        String access = registerAndLogin("frank@example.com", "frank");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"formula\":\"1d20+5\",\"total\":18,\"breakdown\":\"1d20 [13] + 5 = 18\",\"advantage\":false,\"disadvantage\":false}")
                .when().post("/dice-rolls")
                .then()
                .statusCode(201)
                .body("id",       notNullValue())
                .body("formula",  equalTo("1d20+5"))
                .body("total",    is(18))
                .body("ownerId",  notNullValue())
                .body("createdAt", notNullValue());
    }

    @Test
    @DisplayName("POST /dice-rolls: formula vuota → 400")
    void createBlankFormula() {
        String access = registerAndLogin("frank@example.com", "frank");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"formula\":\"\",\"total\":1,\"breakdown\":\"\",\"advantage\":false,\"disadvantage\":false}")
                .when().post("/dice-rolls")
                .then().statusCode(400);
    }

    @Test
    @DisplayName("POST /dice-rolls: senza token → 401")
    void createUnauthenticated() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"formula\":\"1d20\",\"total\":15,\"breakdown\":\"\",\"advantage\":false,\"disadvantage\":false}")
                .when().post("/dice-rolls")
                .then().statusCode(401);
    }

    @Test
    @DisplayName("GET /dice-rolls: lista solo i propri tiri (no leak da altri utenti)")
    void listOnlyOwn() {
        String accFrank = registerAndLogin("frank@example.com", "frank");
        String accOther = registerAndLogin("other@example.com", "other");

        roll(accFrank, "1d20+5",  18, null);
        roll(accFrank, "3d6",     14, null);
        roll(accOther, "1d20-1",   7, null);

        given()
                .header("Authorization", "Bearer " + accFrank)
                .when().get("/dice-rolls")
                .then()
                .statusCode(200)
                .body("$", hasSize(2));
    }

    @Test
    @DisplayName("GET /dice-rolls?characterId=...: filtra per scheda")
    void listFilterByCharacter() {
        String access = registerAndLogin("frank@example.com", "frank");
        // due character id finti (validi formato ObjectId)
        String charA = "507f1f77bcf86cd799439011";
        String charB = "507f1f77bcf86cd799439012";

        roll(access, "1d20+1", 10, charA);
        roll(access, "1d20+2", 11, charB);
        roll(access, "1d20+3", 12, charA);
        roll(access, "1d20+4", 13, null);

        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/dice-rolls?characterId=" + charA)
                .then()
                .statusCode(200)
                .body("$", hasSize(2));
    }

    @Test
    @DisplayName("GET /dice-rolls: ordina per createdAt desc (più recente prima)")
    void listOrderDesc() {
        String access = registerAndLogin("frank@example.com", "frank");

        roll(access, "first",  1, null);
        try { Thread.sleep(10); } catch (InterruptedException e) { /* ignore */ }
        roll(access, "second", 2, null);
        try { Thread.sleep(10); } catch (InterruptedException e) { /* ignore */ }
        roll(access, "third",  3, null);

        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/dice-rolls")
                .then().statusCode(200)
                .body("formula", equalTo(List.of("third","second","first")));
    }

    @Test
    @DisplayName("GET /dice-rolls?limit=1: rispetta il limit")
    void listRespectsLimit() {
        String access = registerAndLogin("frank@example.com", "frank");
        roll(access, "a", 1, null);
        roll(access, "b", 2, null);
        roll(access, "c", 3, null);

        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/dice-rolls?limit=1")
                .then()
                .statusCode(200)
                .body("$", hasSize(1));
    }

    @Test
    @DisplayName("DELETE /dice-rolls: cancella tutti i tiri dell'owner")
    void clearAll() {
        String accFrank = registerAndLogin("frank@example.com", "frank");
        String accOther = registerAndLogin("other@example.com", "other");

        roll(accFrank, "a", 1, null);
        roll(accFrank, "b", 2, null);
        roll(accOther, "c", 3, null);

        given()
                .header("Authorization", "Bearer " + accFrank)
                .when().delete("/dice-rolls")
                .then().statusCode(204);

        // Frank: 0
        given()
                .header("Authorization", "Bearer " + accFrank)
                .when().get("/dice-rolls")
                .then().statusCode(200).body("$", hasSize(0));

        // Other: ancora il suo
        given()
                .header("Authorization", "Bearer " + accOther)
                .when().get("/dice-rolls")
                .then().statusCode(200).body("$", hasSize(1));
    }

    // ---------------- helpers ----------------

    private void roll(String access, String formula, int total, String characterId) {
        String body = characterId == null
                ? "{\"formula\":\"%s\",\"total\":%d,\"breakdown\":\"\",\"advantage\":false,\"disadvantage\":false}"
                        .formatted(formula, total)
                : "{\"formula\":\"%s\",\"total\":%d,\"breakdown\":\"\",\"advantage\":false,\"disadvantage\":false,\"characterId\":\"%s\"}"
                        .formatted(formula, total, characterId);

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/dice-rolls")
                .then().statusCode(201);
    }

    private String registerAndLogin(String email, String username) {
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
