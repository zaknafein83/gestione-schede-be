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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test feature F4.2 — duplicate scheda + portrait su GridFS.
 */
@QuarkusTest
@DisplayName("Feature F4.2 — duplicate + portrait")
class CharacterPortraitAndDuplicateTest {

    private static final Pattern TOKEN_LINK_PATTERN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    private static final byte[] FAKE_PNG = {
            (byte)0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n',
            0x00, 0x00, 0x00, 0x00, 'I','E','N','D', (byte)0xAE, 0x42, 0x60, (byte)0x82
    };

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
        db.getCollection("fs.files").deleteMany(new Document());
        db.getCollection("fs.chunks").deleteMany(new Document());
        mailbox.clear();
    }

    // ==================================================================
    //                          DUPLICATE
    // ==================================================================

    @Test
    @DisplayName("duplicate: scheda senza portrait → 201 + nome con suffisso + id diverso")
    void duplicateBasic() {
        String access = registerAndLogin("frank@example.com", "frank");
        String srcId  = createCharacter(access, """
                {"name":"Mirko","race":"Elfo","level":5,"wis":17,"hpMax":24}
                """);

        String dupId = given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .when().post("/characters/" + srcId + "/duplicate")
                .then()
                .statusCode(201)
                .body("id",    notNullValue())
                .body("name",  equalTo("Mirko (copia)"))
                .body("race",  equalTo("Elfo"))
                .body("level", is(5))
                .body("wis",   is(17))
                .body("hpMax", is(24))
                .body("portraitFileId", nullValue())
                .extract().jsonPath().getString("id");

        assertNotEquals(srcId, dupId);

        // sorgente intatta
        given().header("Authorization", "Bearer " + access)
                .when().get("/characters/" + srcId)
                .then().statusCode(200).body("name", equalTo("Mirko"));

        // lista ne contiene due
        given().header("Authorization", "Bearer " + access)
                .when().get("/characters")
                .then().statusCode(200).body("$.size()", is(2));
    }

    @Test
    @DisplayName("duplicate: scheda senza nome → copia senza nome (no suffisso)")
    void duplicateNoName() {
        String access = registerAndLogin("frank@example.com", "frank");
        String srcId  = createCharacter(access, "{\"race\":\"Nano\"}");

        given()
                .header("Authorization", "Bearer " + access)
                .when().post("/characters/" + srcId + "/duplicate")
                .then()
                .statusCode(201)
                .body("name", nullValue())
                .body("race", equalTo("Nano"));
    }

    @Test
    @DisplayName("duplicate: scheda di un altro utente → 404 (no leak)")
    void duplicateOtherUser() {
        String accessFrank = registerAndLogin("frank@example.com", "frank");
        String accessOther = registerAndLogin("other@example.com", "other");

        String idOther = createCharacter(accessOther, "{\"name\":\"Riservata\"}");

        given()
                .header("Authorization", "Bearer " + accessFrank)
                .when().post("/characters/" + idOther + "/duplicate")
                .then()
                .statusCode(404)
                .body("code", equalTo("CHARACTER_NOT_FOUND"));
    }

    @Test
    @DisplayName("duplicate: senza token → 401")
    void duplicateUnauthenticated() {
        given().when().post("/characters/507f1f77bcf86cd799439011/duplicate")
                .then().statusCode(401);
    }

    @Test
    @DisplayName("duplicate: con portrait → clona il file (2 file su GridFS, id diversi)")
    void duplicateWithPortrait() throws IOException {
        String access = registerAndLogin("frank@example.com", "frank");
        String srcId  = createCharacter(access, "{\"name\":\"Mirko\"}");
        Path tmp = writeTempFile(FAKE_PNG, ".png");

        String srcPortraitId = given()
                .header("Authorization", "Bearer " + access)
                .multiPart("file", tmp.toFile(), "image/png")
                .when().post("/characters/" + srcId + "/portrait")
                .then().statusCode(200)
                .extract().jsonPath().getString("portraitFileId");
        assertNotNull(srcPortraitId);

        String dupPortraitId = given()
                .header("Authorization", "Bearer " + access)
                .when().post("/characters/" + srcId + "/duplicate")
                .then()
                .statusCode(201)
                .body("portraitFileId", notNullValue())
                .extract().jsonPath().getString("portraitFileId");

        assertNotEquals(srcPortraitId, dupPortraitId);

        assertEquals(2, mongoClient.getDatabase(dbName)
                .getCollection("fs.files").countDocuments(),
                "Devono esserci due file portrait distinti su GridFS");
    }

    // ==================================================================
    //                     POST/GET/DELETE PORTRAIT
    // ==================================================================

    @Test
    @DisplayName("portrait upload: happy path → 200 + portraitFileId valorizzato")
    void uploadHappyPath() throws IOException {
        String access = registerAndLogin("frank@example.com", "frank");
        String id     = createCharacter(access, "{\"name\":\"Mirko\"}");
        Path tmp = writeTempFile(FAKE_PNG, ".png");

        given()
                .header("Authorization", "Bearer " + access)
                .multiPart("file", tmp.toFile(), "image/png")
                .when().post("/characters/" + id + "/portrait")
                .then()
                .statusCode(200)
                .body("portraitFileId", notNullValue());

        assertEquals(1, mongoClient.getDatabase(dbName)
                .getCollection("fs.files").countDocuments());
    }

    @Test
    @DisplayName("portrait upload: senza token → 401")
    void uploadUnauthenticated() throws IOException {
        Path tmp = writeTempFile(FAKE_PNG, ".png");
        given()
                .multiPart("file", tmp.toFile(), "image/png")
                .when().post("/characters/507f1f77bcf86cd799439011/portrait")
                .then().statusCode(401);
    }

    @Test
    @DisplayName("portrait upload: scheda altrui → 404")
    void uploadOtherUser() throws IOException {
        String accessFrank = registerAndLogin("frank@example.com", "frank");
        String accessOther = registerAndLogin("other@example.com", "other");
        String idOther = createCharacter(accessOther, "{\"name\":\"Riservata\"}");
        Path tmp = writeTempFile(FAKE_PNG, ".png");

        given()
                .header("Authorization", "Bearer " + accessFrank)
                .multiPart("file", tmp.toFile(), "image/png")
                .when().post("/characters/" + idOther + "/portrait")
                .then()
                .statusCode(404)
                .body("code", equalTo("CHARACTER_NOT_FOUND"));

        // e GridFS non e' stato toccato
        assertEquals(0, mongoClient.getDatabase(dbName)
                .getCollection("fs.files").countDocuments());
    }

    @Test
    @DisplayName("portrait upload: content-type non valido → 400 UNSUPPORTED_MEDIA_TYPE")
    void uploadWrongContentType() throws IOException {
        String access = registerAndLogin("frank@example.com", "frank");
        String id     = createCharacter(access, "{\"name\":\"Mirko\"}");
        Path tmp = writeTempFile("hello".getBytes(), ".txt");

        given()
                .header("Authorization", "Bearer " + access)
                .multiPart("file", tmp.toFile(), "text/plain")
                .when().post("/characters/" + id + "/portrait")
                .then()
                .statusCode(400)
                .body("code", equalTo("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("portrait upload: file troppo grande → 400 FILE_TOO_LARGE")
    void uploadTooLarge() throws IOException {
        String access = registerAndLogin("frank@example.com", "frank");
        String id     = createCharacter(access, "{\"name\":\"Mirko\"}");
        byte[] big = new byte[(int)(2L * 1024 * 1024) + 1];
        Path tmp = writeTempFile(big, ".png");

        given()
                .header("Authorization", "Bearer " + access)
                .multiPart("file", tmp.toFile(), "image/png")
                .when().post("/characters/" + id + "/portrait")
                .then()
                .statusCode(400)
                .body("code", equalTo("FILE_TOO_LARGE"));
    }

    @Test
    @DisplayName("portrait upload: caricare un nuovo ritratto cancella il vecchio")
    void uploadReplacesPrevious() throws IOException {
        String access = registerAndLogin("frank@example.com", "frank");
        String id     = createCharacter(access, "{\"name\":\"Mirko\"}");
        Path tmp = writeTempFile(FAKE_PNG, ".png");

        String first = given()
                .header("Authorization", "Bearer " + access)
                .multiPart("file", tmp.toFile(), "image/png")
                .when().post("/characters/" + id + "/portrait")
                .then().statusCode(200)
                .extract().jsonPath().getString("portraitFileId");

        String second = given()
                .header("Authorization", "Bearer " + access)
                .multiPart("file", tmp.toFile(), "image/png")
                .when().post("/characters/" + id + "/portrait")
                .then().statusCode(200)
                .extract().jsonPath().getString("portraitFileId");

        assertNotEquals(first, second);
        assertEquals(1, mongoClient.getDatabase(dbName)
                .getCollection("fs.files").countDocuments());
    }

    @Test
    @DisplayName("portrait download: nessun ritratto → 404 PORTRAIT_NOT_FOUND")
    void downloadAbsent() {
        String access = registerAndLogin("frank@example.com", "frank");
        String id     = createCharacter(access, "{\"name\":\"Mirko\"}");

        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/characters/" + id + "/portrait")
                .then()
                .statusCode(404)
                .body("code", equalTo("PORTRAIT_NOT_FOUND"));
    }

    @Test
    @DisplayName("portrait download: dopo upload → 200 con content-type corretto + body identico")
    void downloadAfterUpload() throws IOException {
        String access = registerAndLogin("frank@example.com", "frank");
        String id     = createCharacter(access, "{\"name\":\"Mirko\"}");
        Path tmp = writeTempFile(FAKE_PNG, ".png");

        given()
                .header("Authorization", "Bearer " + access)
                .multiPart("file", tmp.toFile(), "image/png")
                .when().post("/characters/" + id + "/portrait")
                .then().statusCode(200);

        byte[] downloaded = given()
                .header("Authorization", "Bearer " + access)
                .when().get("/characters/" + id + "/portrait")
                .then().statusCode(200)
                .contentType("image/png")
                .extract().asByteArray();

        assertEquals(FAKE_PNG.length, downloaded.length);
        for (int i = 0; i < FAKE_PNG.length; i++) {
            assertEquals(FAKE_PNG[i], downloaded[i], "byte " + i + " differente");
        }
    }

    @Test
    @DisplayName("portrait download: scheda altrui → 404 CHARACTER_NOT_FOUND")
    void downloadOtherUser() throws IOException {
        String accessFrank = registerAndLogin("frank@example.com", "frank");
        String accessOther = registerAndLogin("other@example.com", "other");
        String idOther = createCharacter(accessOther, "{\"name\":\"Riservata\"}");
        Path tmp = writeTempFile(FAKE_PNG, ".png");

        // l'altro carica un portrait
        given()
                .header("Authorization", "Bearer " + accessOther)
                .multiPart("file", tmp.toFile(), "image/png")
                .when().post("/characters/" + idOther + "/portrait")
                .then().statusCode(200);

        // Frank prova a leggerlo
        given()
                .header("Authorization", "Bearer " + accessFrank)
                .when().get("/characters/" + idOther + "/portrait")
                .then()
                .statusCode(404)
                .body("code", equalTo("CHARACTER_NOT_FOUND"));
    }

    @Test
    @DisplayName("portrait delete: idempotente quando assente → 204")
    void deleteIdempotent() {
        String access = registerAndLogin("frank@example.com", "frank");
        String id     = createCharacter(access, "{\"name\":\"Mirko\"}");

        given()
                .header("Authorization", "Bearer " + access)
                .when().delete("/characters/" + id + "/portrait")
                .then().statusCode(204);
    }

    @Test
    @DisplayName("portrait delete: dopo upload → 204 + GridFS svuotato + download 404")
    void deleteAfterUpload() throws IOException {
        String access = registerAndLogin("frank@example.com", "frank");
        String id     = createCharacter(access, "{\"name\":\"Mirko\"}");
        Path tmp = writeTempFile(FAKE_PNG, ".png");

        given()
                .header("Authorization", "Bearer " + access)
                .multiPart("file", tmp.toFile(), "image/png")
                .when().post("/characters/" + id + "/portrait")
                .then().statusCode(200);

        given()
                .header("Authorization", "Bearer " + access)
                .when().delete("/characters/" + id + "/portrait")
                .then().statusCode(204);

        assertEquals(0, mongoClient.getDatabase(dbName)
                .getCollection("fs.files").countDocuments());

        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/characters/" + id + "/portrait")
                .then().statusCode(404);
    }

    @Test
    @DisplayName("DELETE scheda con portrait: cancella anche il file su GridFS")
    void deleteCharacterCleansPortrait() throws IOException {
        String access = registerAndLogin("frank@example.com", "frank");
        String id     = createCharacter(access, "{\"name\":\"Mirko\"}");
        Path tmp = writeTempFile(FAKE_PNG, ".png");

        given()
                .header("Authorization", "Bearer " + access)
                .multiPart("file", tmp.toFile(), "image/png")
                .when().post("/characters/" + id + "/portrait")
                .then().statusCode(200);
        assertEquals(1, mongoClient.getDatabase(dbName)
                .getCollection("fs.files").countDocuments());

        given()
                .header("Authorization", "Bearer " + access)
                .when().delete("/characters/" + id)
                .then().statusCode(204);

        assertEquals(0, mongoClient.getDatabase(dbName)
                .getCollection("fs.files").countDocuments(),
                "Il file portrait deve essere stato rimosso dopo la delete della scheda");
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

    private Path writeTempFile(byte[] data, String suffix) throws IOException {
        Path tmp = Files.createTempFile("portrait-test-", suffix);
        Files.write(tmp, data);
        tmp.toFile().deleteOnExit();
        return tmp;
    }
}
