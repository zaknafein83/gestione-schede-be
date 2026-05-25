package it.fsisca.dndsheets.character;

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
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Test feature F4.1 — CRUD scheda personaggio (statica, niente calcoli).
 */
@QuarkusTest
@DisplayName("Feature F4.1 — CRUD scheda personaggio")
class CharacterResourceTest {

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
    //                            CREATE
    // ==================================================================

    @Test
    @DisplayName("POST /characters: scheda vuota → 201 + id + ownerId del chiamante")
    void createEmptyOk() {
        String access = registerAndLogin("frank@example.com", "frank");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/characters")
                .then()
                .statusCode(201)
                .body("id",      notNullValue())
                .body("ownerId", notNullValue())
                .body("name",    nullValue())
                .body("createdAt", notNullValue());
    }

    @Test
    @DisplayName("POST /characters: scheda con dati di anagrafica → echo corretto")
    void createWithAnagrafica() {
        String access = registerAndLogin("frank@example.com", "frank");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("""
                      {
                        "name":"Mirko",
                        "race":"Elfo",
                        "className":"Druido",
                        "level":3,
                        "str":10,"dex":14,"con":12,"intel":11,"wis":17,"cha":9,
                        "hpMax":24,"hpCurrent":24,"inspiration":true
                      }
                      """)
                .when().post("/characters")
                .then()
                .statusCode(201)
                .body("name",        equalTo("Mirko"))
                .body("race",        equalTo("Elfo"))
                .body("className",   equalTo("Druido"))
                .body("level",       is(3))
                .body("wis",         is(17))
                .body("hpMax",       is(24))
                .body("inspiration", is(true));
    }

    @Test
    @DisplayName("POST /characters: senza token → 401")
    void createUnauthenticated() {
        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/characters")
                .then().statusCode(401);
    }

    @Test
    @DisplayName("POST /characters: level fuori range (21) → 400")
    void createLevelOutOfRange() {
        String access = registerAndLogin("frank@example.com", "frank");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"level\":21}")
                .when().post("/characters")
                .then().statusCode(400);
    }

    @Test
    @DisplayName("POST /characters: ability fuori range (str=0) → 400")
    void createAbilityOutOfRange() {
        String access = registerAndLogin("frank@example.com", "frank");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"str\":0}")
                .when().post("/characters")
                .then().statusCode(400);
    }

    @Test
    @DisplayName("POST /characters: deathSavesSuccesses=4 → 400")
    void createDeathSavesOutOfRange() {
        String access = registerAndLogin("frank@example.com", "frank");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"deathSavesSuccesses\":4}")
                .when().post("/characters")
                .then().statusCode(400);
    }

    // ==================================================================
    //                             LIST
    // ==================================================================

    @Test
    @DisplayName("GET /characters: lista solo le proprie schede, ordinate per updatedAt desc")
    void listOnlyOwn() {
        String accessFrank = registerAndLogin("frank@example.com", "frank");
        String accessOther = registerAndLogin("other@example.com", "other");

        createCharacter(accessFrank, "{\"name\":\"Frank A\"}");
        createCharacter(accessFrank, "{\"name\":\"Frank B\"}");
        createCharacter(accessOther, "{\"name\":\"Other A\"}");

        given()
                .header("Authorization", "Bearer " + accessFrank)
                .when().get("/characters")
                .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("name", equalTo(List.of("Frank B", "Frank A")));
    }

    @Test
    @DisplayName("GET /characters: senza token → 401")
    void listUnauthenticated() {
        given().when().get("/characters").then().statusCode(401);
    }

    // ==================================================================
    //                              GET
    // ==================================================================

    @Test
    @DisplayName("GET /characters/{id}: scheda propria → 200 con campi completi")
    void getOwn() {
        String access = registerAndLogin("frank@example.com", "frank");
        String id = createCharacter(access, "{\"name\":\"Mirko\",\"level\":5}");

        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/characters/" + id)
                .then()
                .statusCode(200)
                .body("id",    equalTo(id))
                .body("name",  equalTo("Mirko"))
                .body("level", is(5));
    }

    @Test
    @DisplayName("GET /characters/{id}: scheda di un altro utente → 404 (no leak)")
    void getOtherUserCharacter() {
        String accessFrank = registerAndLogin("frank@example.com", "frank");
        String accessOther = registerAndLogin("other@example.com", "other");

        String idOther = createCharacter(accessOther, "{\"name\":\"Riservata\"}");

        given()
                .header("Authorization", "Bearer " + accessFrank)
                .when().get("/characters/" + idOther)
                .then()
                .statusCode(404)
                .body("code", equalTo("CHARACTER_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /characters/{id}: id formalmente invalido → 404")
    void getMalformedId() {
        String access = registerAndLogin("frank@example.com", "frank");

        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/characters/non-un-objectid")
                .then()
                .statusCode(404)
                .body("code", equalTo("CHARACTER_NOT_FOUND"));
    }

    // ==================================================================
    //                             UPDATE
    // ==================================================================

    @Test
    @DisplayName("PATCH /characters/{id}: aggiorna solo i campi forniti")
    void updatePartial() {
        String access = registerAndLogin("frank@example.com", "frank");
        String id = createCharacter(access, """
                {"name":"Mirko","race":"Elfo","level":3,"wis":17}
                """);

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"level\":4}")
                .when().patch("/characters/" + id)
                .then()
                .statusCode(200)
                .body("level", is(4))
                .body("name",  equalTo("Mirko"))
                .body("race",  equalTo("Elfo"))
                .body("wis",   is(17));
    }

    @Test
    @DisplayName("PATCH /characters/{id}: aggiornamento bumpa updatedAt")
    void updateBumpsUpdatedAt() {
        String access = registerAndLogin("frank@example.com", "frank");
        String id = createCharacter(access, "{\"name\":\"Mirko\"}");

        String createdAt = given()
                .header("Authorization", "Bearer " + access)
                .when().get("/characters/" + id)
                .then().extract().jsonPath().getString("updatedAt");

        // breve pausa per garantire timestamp diverso
        try { Thread.sleep(10); } catch (InterruptedException e) { /* ignore */ }

        String updatedAt = given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"hpMax\":20}")
                .when().patch("/characters/" + id)
                .then().statusCode(200)
                .extract().jsonPath().getString("updatedAt");

        org.junit.jupiter.api.Assertions.assertNotEquals(createdAt, updatedAt);
    }

    @Test
    @DisplayName("PATCH /characters/{id}: scheda di altri → 404 (no leak)")
    void updateOtherUserCharacter() {
        String accessFrank = registerAndLogin("frank@example.com", "frank");
        String accessOther = registerAndLogin("other@example.com", "other");

        String idOther = createCharacter(accessOther, "{\"name\":\"Riservata\"}");

        given()
                .header("Authorization", "Bearer " + accessFrank)
                .contentType(ContentType.JSON)
                .body("{\"name\":\"hijack\"}")
                .when().patch("/characters/" + idOther)
                .then()
                .statusCode(404)
                .body("code", equalTo("CHARACTER_NOT_FOUND"));
    }

    @Test
    @DisplayName("PATCH /characters/{id}: conditions vengono salvate (con dedup)")
    void updateConditions() {
        String access = registerAndLogin("frank@example.com", "frank");
        String id = createCharacter(access, "{\"name\":\"Mirko\"}");

        // PATCH con duplicati e una stringa vuota -> dedup + filter
        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"conditions\":[\"blinded\",\"poisoned\",\"blinded\",\"\"]}")
                .when().patch("/characters/" + id)
                .then()
                .statusCode(200)
                .body("conditions", equalTo(List.of("blinded","poisoned")));

        // GET conferma persistenza
        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/characters/" + id)
                .then()
                .statusCode(200)
                .body("conditions", equalTo(List.of("blinded","poisoned")));

        // PATCH con array vuoto -> azzera
        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"conditions\":[]}")
                .when().patch("/characters/" + id)
                .then()
                .statusCode(200)
                .body("conditions.size()", is(0));
    }

    @Test
    @DisplayName("PATCH /characters/{id}: validazione (level=0) → 400")
    void updateInvalidValue() {
        String access = registerAndLogin("frank@example.com", "frank");
        String id = createCharacter(access, "{\"name\":\"Mirko\"}");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"level\":0}")
                .when().patch("/characters/" + id)
                .then().statusCode(400);
    }

    // ==================================================================
    //                             DELETE
    // ==================================================================

    @Test
    @DisplayName("DELETE /characters/{id}: propria → 204 e rimossa dalla lista")
    void deleteOwn() {
        String access = registerAndLogin("frank@example.com", "frank");
        String id = createCharacter(access, "{\"name\":\"Mirko\"}");

        given()
                .header("Authorization", "Bearer " + access)
                .when().delete("/characters/" + id)
                .then().statusCode(204);

        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/characters/" + id)
                .then().statusCode(404);

        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/characters")
                .then().statusCode(200).body("$", hasSize(0));
    }

    @Test
    @DisplayName("DELETE /characters/{id}: scheda di altri → 404")
    void deleteOtherUserCharacter() {
        String accessFrank = registerAndLogin("frank@example.com", "frank");
        String accessOther = registerAndLogin("other@example.com", "other");

        String idOther = createCharacter(accessOther, "{\"name\":\"Riservata\"}");

        given()
                .header("Authorization", "Bearer " + accessFrank)
                .when().delete("/characters/" + idOther)
                .then().statusCode(404);

        // E la scheda dell'altro utente esiste ancora
        given()
                .header("Authorization", "Bearer " + accessOther)
                .when().get("/characters/" + idOther)
                .then().statusCode(200);
    }

    // ==================================================================
    //                            helpers
    // ==================================================================

    private String createCharacter(String access, String jsonBody) {
        return given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body(jsonBody)
                .when().post("/characters")
                .then().statusCode(201)
                .extract().jsonPath().getString("id");
    }

    /**
     * Registra l'utente, verifica la mail e fa login. Ritorna l'access token.
     */
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
