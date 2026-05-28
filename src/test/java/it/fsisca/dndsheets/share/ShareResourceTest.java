package it.fsisca.dndsheets.share;

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

@QuarkusTest
@DisplayName("Feature M4-A — condivisione read-only")
class ShareResourceTest {

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
        db.getCollection("share_tokens").deleteMany(new Document());
        mailbox.clear();
    }

    @Test
    @DisplayName("POST /characters/{id}/shares: 201 + token in chiaro")
    void createOk() {
        String access = registerAndLogin("frank@example.com", "frank");
        String charId = createCharacter(access, "{\"name\":\"Mirko\",\"level\":5}");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .when().post("/characters/" + charId + "/shares")
                .then()
                .statusCode(201)
                .body("id",          notNullValue())
                .body("characterId", equalTo(charId))
                .body("revoked",     is(false))
                .body("token",       notNullValue());
    }

    @Test
    @DisplayName("POST /characters/{id}/shares: scheda altrui → 404")
    void createOtherUser() {
        String accFrank = registerAndLogin("frank@example.com", "frank");
        String accOther = registerAndLogin("other@example.com", "other");
        String charId = createCharacter(accOther, "{\"name\":\"Riservata\"}");

        given()
                .header("Authorization", "Bearer " + accFrank)
                .contentType(ContentType.JSON)
                .when().post("/characters/" + charId + "/shares")
                .then().statusCode(404);
    }

    @Test
    @DisplayName("GET /share/{token}: pubblico, restituisce la scheda")
    void publicViewOk() {
        String access = registerAndLogin("frank@example.com", "frank");
        String charId = createCharacter(access, "{\"name\":\"Mirko\",\"level\":5,\"race\":\"Elfo\"}");
        String token  = generateShare(access, charId);

        // SENZA Authorization header — pubblico
        given()
                .when().get("/share/" + token)
                .then()
                .statusCode(200)
                .body("name",      equalTo("Mirko"))
                .body("level",     is(5))
                .body("race",      equalTo("Elfo"));
    }

    @Test
    @DisplayName("GET /share/{token}: token sconosciuto → 404")
    void publicViewUnknownToken() {
        given()
                .when().get("/share/non-esiste-questo-token-xyz")
                .then()
                .statusCode(404)
                .body("code", equalTo("SHARE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Rigenerazione: il vecchio token diventa invalido")
    void regenerateInvalidatesOld() {
        String access = registerAndLogin("frank@example.com", "frank");
        String charId = createCharacter(access, "{\"name\":\"Mirko\"}");

        String t1 = generateShare(access, charId);
        // Subito leggibile
        given().when().get("/share/" + t1).then().statusCode(200);

        // Genera un nuovo token: il vecchio va invalidato
        String t2 = generateShare(access, charId);
        given().when().get("/share/" + t1).then().statusCode(404);
        given().when().get("/share/" + t2).then().statusCode(200);
    }

    @Test
    @DisplayName("DELETE share: revoca il token, GET pubblico → 404")
    void revokeOk() {
        String access = registerAndLogin("frank@example.com", "frank");
        String charId = createCharacter(access, "{\"name\":\"Mirko\"}");

        var resp = given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .when().post("/characters/" + charId + "/shares")
                .then().statusCode(201).extract().jsonPath();
        String token   = resp.getString("token");
        String shareId = resp.getString("id");

        given().when().get("/share/" + token).then().statusCode(200);

        given()
                .header("Authorization", "Bearer " + access)
                .when().delete("/characters/" + charId + "/shares/" + shareId)
                .then().statusCode(204);

        given().when().get("/share/" + token).then().statusCode(404);
    }

    @Test
    @DisplayName("GET shares (auth): lista include attivi e revocati")
    void listIncludesRevoked() {
        String access = registerAndLogin("frank@example.com", "frank");
        String charId = createCharacter(access, "{\"name\":\"Mirko\"}");

        generateShare(access, charId); // 1° (verra' revocato automaticamente)
        generateShare(access, charId); // 2° (attivo)

        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/characters/" + charId + "/shares")
                .then()
                .statusCode(200)
                .body("$", hasSize(2));
    }

    @Test
    @DisplayName("DELETE share di un altro utente → 404 (no leak)")
    void revokeOtherUserShare() {
        String accFrank = registerAndLogin("frank@example.com", "frank");
        String accOther = registerAndLogin("other@example.com", "other");
        String charId = createCharacter(accOther, "{\"name\":\"Riservata\"}");

        // L'altro crea uno share, Frank prova a cancellarlo via path manipulato
        var resp = given()
                .header("Authorization", "Bearer " + accOther)
                .contentType(ContentType.JSON)
                .when().post("/characters/" + charId + "/shares")
                .then().statusCode(201).extract().jsonPath();
        String shareId = resp.getString("id");

        // Frank prova a cancellarlo passando il characterId di Other → 404 (Other-charId non gli appartiene)
        given()
                .header("Authorization", "Bearer " + accFrank)
                .when().delete("/characters/" + charId + "/shares/" + shareId)
                .then().statusCode(404);
    }

    @Test
    @DisplayName("POST/GET/DELETE shares senza token → 401")
    void unauthorized() {
        given().contentType(ContentType.JSON)
                .when().post("/characters/507f1f77bcf86cd799439011/shares")
                .then().statusCode(401);
        given().when().get("/characters/507f1f77bcf86cd799439011/shares")
                .then().statusCode(401);
        given().when().delete("/characters/507f1f77bcf86cd799439011/shares/507f1f77bcf86cd799439012")
                .then().statusCode(401);
    }

    // ---------------- helpers ----------------

    private String generateShare(String access, String charId) {
        return given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .when().post("/characters/" + charId + "/shares")
                .then().statusCode(201)
                .extract().jsonPath().getString("token");
    }

    private String createCharacter(String access, String body) {
        return given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body(body)
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
