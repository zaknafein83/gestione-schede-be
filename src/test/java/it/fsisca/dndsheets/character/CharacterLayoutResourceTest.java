package it.fsisca.dndsheets.character;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
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
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test feature dashboard custom — endpoint
 * GET/PUT/DELETE {@code /characters/{id}/layout}.
 * Coperture: default layout, ownership, premium gate, validation,
 * cascade su delete scheda.
 */
@QuarkusTest
@DisplayName("Dashboard custom — layout endpoint")
class CharacterLayoutResourceTest {

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
        db.getCollection("character_layouts").deleteMany(new Document());
        mailbox.clear();
    }

    // ==================================================================
    //                              GET
    // ==================================================================

    @Test
    @DisplayName("GET /characters/{id}/layout: scheda senza layout → 200 isDefault=true")
    void getDefaultLayoutForFreshCharacter() {
        String access = registerAndLogin("frank@example.com", "frank");
        String id = createCharacter(access, "{\"name\":\"Mirko\"}");

        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/characters/" + id + "/layout")
                .then()
                .statusCode(200)
                .body("isDefault",   is(true))
                .body("id",          nullValue())
                .body("widgets",     hasSize(0));
    }

    @Test
    @DisplayName("GET /characters/{id}/layout: scheda di altro utente → 404")
    void getLayoutForOtherUserCharacter() {
        String accessFrank = registerAndLogin("frank@example.com", "frank");
        String accessOther = registerAndLogin("other@example.com", "other");
        String idOther = createCharacter(accessOther, "{\"name\":\"Riservata\"}");

        given()
                .header("Authorization", "Bearer " + accessFrank)
                .when().get("/characters/" + idOther + "/layout")
                .then()
                .statusCode(404)
                .body("code", equalTo("CHARACTER_NOT_FOUND"));
    }

    // ==================================================================
    //                          PUT — premium gate
    // ==================================================================

    @Test
    @DisplayName("PUT layout: utente FREE → 402 TIER_LIMIT_REACHED")
    void freeUserCannotSaveLayout() {
        String access = registerAndLogin("frank@example.com", "frank");
        String id = createCharacter(access, "{\"name\":\"Mirko\"}");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("""
                      {"widgets":[
                        {"type":"STATS","x":0,"y":0,"w":4,"h":4,"z":0}
                      ]}
                      """)
                .when().put("/characters/" + id + "/layout")
                .then()
                .statusCode(402)
                .body("code", equalTo("TIER_LIMIT_REACHED"));

        // GET non deve trovare nulla — il salvataggio non è avvenuto
        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/characters/" + id + "/layout")
                .then()
                .statusCode(200)
                .body("isDefault", is(true));
    }

    @Test
    @DisplayName("PUT layout: utente PREMIUM → 200 + layout salvato")
    void premiumUserCanSaveLayout() {
        String access = registerAndLogin("frank@example.com", "frank");
        promoteToPremium("frank@example.com");
        String id = createCharacter(access, "{\"name\":\"Mirko\"}");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("""
                      {"widgets":[
                        {"type":"STATS","x":0,"y":0,"w":4,"h":4,"z":0},
                        {"type":"COMBAT","x":4,"y":0,"w":4,"h":4,"z":1}
                      ]}
                      """)
                .when().put("/characters/" + id + "/layout")
                .then()
                .statusCode(200)
                .body("isDefault",         is(false))
                .body("id",                notNullValue())
                .body("widgets",           hasSize(2))
                .body("widgets[0].type",   equalTo("STATS"))
                .body("widgets[1].type",   equalTo("COMBAT"));

        // GET deve ritornare lo stesso layout
        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/characters/" + id + "/layout")
                .then()
                .statusCode(200)
                .body("isDefault",       is(false))
                .body("widgets",         hasSize(2))
                .body("widgets[0].type", equalTo("STATS"));
    }

    @Test
    @DisplayName("PUT layout: secondo save sovrascrive il primo (no record duplicati)")
    void putLayoutOverwritesExisting() {
        String access = registerAndLogin("frank@example.com", "frank");
        promoteToPremium("frank@example.com");
        String id = createCharacter(access, "{\"name\":\"Mirko\"}");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"widgets\":[{\"type\":\"STATS\",\"x\":0,\"y\":0,\"w\":2,\"h\":2,\"z\":0}]}")
                .when().put("/characters/" + id + "/layout")
                .then().statusCode(200);

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("""
                      {"widgets":[
                        {"type":"NOTES","x":0,"y":0,"w":8,"h":6,"z":0}
                      ]}
                      """)
                .when().put("/characters/" + id + "/layout")
                .then()
                .statusCode(200)
                .body("widgets",         hasSize(1))
                .body("widgets[0].type", equalTo("NOTES"));

        // Solo 1 documento per (ownerId, characterId)
        long count = mongoClient.getDatabase(dbName)
                .getCollection("character_layouts")
                .countDocuments();
        assertEquals(1L, count, "Atteso 1 layout per (owner, character), no duplicati");
    }

    // ==================================================================
    //                       PUT — validazione widgets
    // ==================================================================

    @Test
    @DisplayName("PUT layout: tipo widget sconosciuto → 400 INVALID_WIDGET_TYPE")
    void putLayoutInvalidType() {
        String access = registerAndLogin("frank@example.com", "frank");
        promoteToPremium("frank@example.com");
        String id = createCharacter(access, "{\"name\":\"Mirko\"}");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("""
                      {"widgets":[
                        {"type":"PIPPO","x":0,"y":0,"w":4,"h":4,"z":0}
                      ]}
                      """)
                .when().put("/characters/" + id + "/layout")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_WIDGET_TYPE"));
    }

    @Test
    @DisplayName("PUT layout: due widget dello stesso tipo → 400 DUPLICATE_WIDGET_TYPE")
    void putLayoutDuplicateType() {
        String access = registerAndLogin("frank@example.com", "frank");
        promoteToPremium("frank@example.com");
        String id = createCharacter(access, "{\"name\":\"Mirko\"}");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("""
                      {"widgets":[
                        {"type":"STATS","x":0,"y":0,"w":4,"h":4,"z":0},
                        {"type":"STATS","x":4,"y":0,"w":4,"h":4,"z":1}
                      ]}
                      """)
                .when().put("/characters/" + id + "/layout")
                .then()
                .statusCode(400)
                .body("code", equalTo("DUPLICATE_WIDGET_TYPE"));
    }

    @Test
    @DisplayName("PUT layout: coordinate negative → 400 VALIDATION_FAILED")
    void putLayoutNegativeCoordinatesValidationFails() {
        String access = registerAndLogin("frank@example.com", "frank");
        promoteToPremium("frank@example.com");
        String id = createCharacter(access, "{\"name\":\"Mirko\"}");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("""
                      {"widgets":[
                        {"type":"STATS","x":-1,"y":0,"w":4,"h":4,"z":0}
                      ]}
                      """)
                .when().put("/characters/" + id + "/layout")
                .then()
                .statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"));
    }

    // ==================================================================
    //                              DELETE
    // ==================================================================

    @Test
    @DisplayName("DELETE layout: rimuove e GET torna a isDefault")
    void deleteLayoutResetsToDefault() {
        String access = registerAndLogin("frank@example.com", "frank");
        promoteToPremium("frank@example.com");
        String id = createCharacter(access, "{\"name\":\"Mirko\"}");

        // Salva un layout
        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"widgets\":[{\"type\":\"STATS\",\"x\":0,\"y\":0,\"w\":2,\"h\":2,\"z\":0}]}")
                .when().put("/characters/" + id + "/layout")
                .then().statusCode(200);

        // Delete (anche se FREE può chiamare delete: è solo reset)
        given()
                .header("Authorization", "Bearer " + access)
                .when().delete("/characters/" + id + "/layout")
                .then().statusCode(204);

        // GET → torna a default
        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/characters/" + id + "/layout")
                .then()
                .statusCode(200)
                .body("isDefault", is(true));
    }

    // ==================================================================
    //                          Cascade
    // ==================================================================

    @Test
    @DisplayName("DELETE scheda: il layout della scheda viene cancellato (cascade)")
    void deleteCharacterCascadesLayout() {
        String access = registerAndLogin("frank@example.com", "frank");
        promoteToPremium("frank@example.com");
        String id = createCharacter(access, "{\"name\":\"Mirko\"}");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"widgets\":[{\"type\":\"STATS\",\"x\":0,\"y\":0,\"w\":2,\"h\":2,\"z\":0}]}")
                .when().put("/characters/" + id + "/layout")
                .then().statusCode(200);

        long beforeCount = mongoClient.getDatabase(dbName)
                .getCollection("character_layouts").countDocuments();
        assertEquals(1L, beforeCount);

        // Cancella la scheda
        given()
                .header("Authorization", "Bearer " + access)
                .when().delete("/characters/" + id)
                .then().statusCode(204);

        long afterCount = mongoClient.getDatabase(dbName)
                .getCollection("character_layouts").countDocuments();
        assertEquals(0L, afterCount,
                "Atteso: layout cancellato in cascade quando si cancella la scheda");
    }

    @Test
    @DisplayName("Utente PREMIUM declassato a FREE: GET ritorna comunque il layout salvato")
    void downgradedUserStillSeesPreviouslySavedLayout() {
        String access = registerAndLogin("frank@example.com", "frank");
        promoteToPremium("frank@example.com");
        String id = createCharacter(access, "{\"name\":\"Mirko\"}");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"widgets\":[{\"type\":\"STATS\",\"x\":0,\"y\":0,\"w\":2,\"h\":2,\"z\":0}]}")
                .when().put("/characters/" + id + "/layout")
                .then().statusCode(200);

        // Declassa
        demoteToFree("frank@example.com");

        // GET continua a funzionare
        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/characters/" + id + "/layout")
                .then()
                .statusCode(200)
                .body("isDefault", is(false))
                .body("widgets",   hasSize(greaterThan(0)));

        // Ma PUT non funziona più
        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"widgets\":[{\"type\":\"NOTES\",\"x\":0,\"y\":0,\"w\":8,\"h\":6,\"z\":0}]}")
                .when().put("/characters/" + id + "/layout")
                .then().statusCode(402);
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

    private void demoteToFree(String email) {
        mongoClient.getDatabase(dbName).getCollection("users").updateOne(
                Filters.eq("email", email),
                Updates.combine(
                        Updates.set("tier", "FREE"),
                        Updates.set("premiumSince", null),
                        Updates.set("premiumSource", null)));
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
                      {"email":"%s","password":"Password123","username":"%s","displayName":"%s","acceptPrivacy":true}
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
