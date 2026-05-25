package it.fsisca.dndsheets.profile;

import com.mongodb.client.MongoClient;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.parsing.Parser;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
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
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Test feature F3.2 — upload/download/delete avatar via GridFS.
 */
@QuarkusTest
@DisplayName("Feature F3.2 — avatar (GridFS)")
class AvatarResourceTest {

    private static final Pattern TOKEN_LINK_PATTERN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    // Magic bytes minimi di un PNG (header + IEND). Il backend non valida i bytes,
    // solo il content-type. Va bene per testing.
    private static final byte[] FAKE_PNG = {
            (byte)0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n',
            // dummy payload
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
        db.getCollection("fs.files").deleteMany(new Document());
        db.getCollection("fs.chunks").deleteMany(new Document());
        mailbox.clear();
    }

    // ==================================================================
    //                       POST /me/avatar
    // ==================================================================

    @Test
    @DisplayName("upload: happy path → 200 + file in GridFS")
    void uploadHappyPath() throws IOException {
        var tok = registerAndLogin("frank@example.com", "frank");
        Path tmp = writeTempFile(FAKE_PNG, ".png");

        given()
                .header("Authorization", "Bearer " + tok.access)
                .multiPart("file", tmp.toFile(), "image/png")
                .when().post("/me/avatar")
                .then()
                .statusCode(200)
                .body("email", equalTo("frank@example.com"));

        // verifico in DB che ci sia esattamente 1 file in GridFS
        assertEquals(1, mongoClient.getDatabase(dbName)
                .getCollection("fs.files").countDocuments());
    }

    @Test
    @DisplayName("upload: senza auth → 401")
    void uploadUnauthenticated() throws IOException {
        Path tmp = writeTempFile(FAKE_PNG, ".png");
        given()
                .multiPart("file", tmp.toFile(), "image/png")
                .when().post("/me/avatar")
                .then().statusCode(401);
    }

    @Test
    @DisplayName("upload: content-type non supportato → 400 UNSUPPORTED_MEDIA_TYPE")
    void uploadWrongContentType() throws IOException {
        var tok = registerAndLogin("frank@example.com", "frank");
        Path tmp = writeTempFile("hello".getBytes(), ".txt");

        given()
                .header("Authorization", "Bearer " + tok.access)
                .multiPart("file", tmp.toFile(), "text/plain")
                .when().post("/me/avatar")
                .then()
                .statusCode(400)
                .body("code", equalTo("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("upload: file troppo grande → 400 FILE_TOO_LARGE")
    void uploadTooLarge() throws IOException {
        var tok = registerAndLogin("frank@example.com", "frank");
        byte[] big = new byte[(int)(2L * 1024 * 1024) + 1]; // 2MiB + 1 byte
        Path tmp = writeTempFile(big, ".png");

        given()
                .header("Authorization", "Bearer " + tok.access)
                .multiPart("file", tmp.toFile(), "image/png")
                .when().post("/me/avatar")
                .then()
                .statusCode(400)
                .body("code", equalTo("FILE_TOO_LARGE"));
    }

    @Test
    @DisplayName("upload: caricare un nuovo avatar cancella il vecchio da GridFS")
    void uploadReplacesPrevious() throws IOException {
        var tok = registerAndLogin("frank@example.com", "frank");
        Path tmp = writeTempFile(FAKE_PNG, ".png");

        // primo upload
        given()
                .header("Authorization", "Bearer " + tok.access)
                .multiPart("file", tmp.toFile(), "image/png")
                .when().post("/me/avatar")
                .then().statusCode(200);

        ObjectId firstId = currentAvatarId("frank@example.com");
        assertNotNull(firstId);

        // secondo upload
        given()
                .header("Authorization", "Bearer " + tok.access)
                .multiPart("file", tmp.toFile(), "image/png")
                .when().post("/me/avatar")
                .then().statusCode(200);

        ObjectId secondId = currentAvatarId("frank@example.com");
        assertNotNull(secondId);

        // user punta al nuovo
        assertEquals(false, firstId.equals(secondId), "fileId deve essere cambiato");

        // ed esiste un solo file in GridFS (il nuovo)
        assertEquals(1, mongoClient.getDatabase(dbName)
                .getCollection("fs.files").countDocuments());
    }

    // ==================================================================
    //                        GET /me/avatar
    // ==================================================================

    @Test
    @DisplayName("download: nessun avatar → 404 AVATAR_NOT_FOUND")
    void downloadWhenAbsent() {
        var tok = registerAndLogin("frank@example.com", "frank");

        given()
                .header("Authorization", "Bearer " + tok.access)
                .when().get("/me/avatar")
                .then()
                .statusCode(404)
                .body("code", equalTo("AVATAR_NOT_FOUND"));
    }

    @Test
    @DisplayName("download: dopo upload → 200 con content-type corretto + body identico")
    void downloadAfterUpload() throws IOException {
        var tok = registerAndLogin("frank@example.com", "frank");
        Path tmp = writeTempFile(FAKE_PNG, ".png");

        given()
                .header("Authorization", "Bearer " + tok.access)
                .multiPart("file", tmp.toFile(), "image/png")
                .when().post("/me/avatar")
                .then().statusCode(200);

        byte[] downloaded = given()
                .header("Authorization", "Bearer " + tok.access)
                .when().get("/me/avatar")
                .then()
                .statusCode(200)
                .contentType("image/png")
                .extract().asByteArray();

        assertEquals(FAKE_PNG.length, downloaded.length);
        for (int i = 0; i < FAKE_PNG.length; i++) {
            assertEquals(FAKE_PNG[i], downloaded[i], "byte " + i + " differente");
        }
    }

    @Test
    @DisplayName("download: senza auth → 401")
    void downloadUnauthenticated() {
        given().when().get("/me/avatar").then().statusCode(401);
    }

    // ==================================================================
    //                       DELETE /me/avatar
    // ==================================================================

    @Test
    @DisplayName("delete: idempotente quando non c'e' avatar → 204")
    void deleteIdempotent() {
        var tok = registerAndLogin("frank@example.com", "frank");

        given()
                .header("Authorization", "Bearer " + tok.access)
                .when().delete("/me/avatar")
                .then().statusCode(204);
    }

    @Test
    @DisplayName("delete: dopo upload → 204 + GridFS svuotato + successivo download 404")
    void deleteAfterUpload() throws IOException {
        var tok = registerAndLogin("frank@example.com", "frank");
        Path tmp = writeTempFile(FAKE_PNG, ".png");

        given()
                .header("Authorization", "Bearer " + tok.access)
                .multiPart("file", tmp.toFile(), "image/png")
                .when().post("/me/avatar")
                .then().statusCode(200);

        given()
                .header("Authorization", "Bearer " + tok.access)
                .when().delete("/me/avatar")
                .then().statusCode(204);

        assertNull(currentAvatarId("frank@example.com"));
        assertEquals(0, mongoClient.getDatabase(dbName)
                .getCollection("fs.files").countDocuments());

        given()
                .header("Authorization", "Bearer " + tok.access)
                .when().get("/me/avatar")
                .then().statusCode(404);
    }

    // ==================================================================
    //                            helpers
    // ==================================================================

    private record Tokens(String access, String refresh) {}

    private Tokens registerAndLogin(String email, String username) {
        given()
                .contentType(ContentType.JSON)
                .body("""
                      {"email":"%s","password":"Password123","username":"%s","displayName":"Frank S.","acceptPrivacy":true}
                      """.formatted(email, username))
                .when().post("/auth/register")
                .then().statusCode(201);

        String evt = extractTokenFromMailboxFor(email);
        given()
                .contentType(ContentType.JSON)
                .body("{\"token\":\"" + evt + "\"}")
                .when().post("/auth/verify-email")
                .then().statusCode(200);

        var path = given()
                .contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"password\":\"Password123\"}")
                .when().post("/auth/login")
                .then().statusCode(200).extract().jsonPath();

        return new Tokens(path.getString("accessToken"), path.getString("refreshToken"));
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
        Path tmp = Files.createTempFile("avatar-test-", suffix);
        Files.write(tmp, data);
        tmp.toFile().deleteOnExit();
        return tmp;
    }

    /** Recupera l'avatarFileId direttamente dal DB. */
    private ObjectId currentAvatarId(String email) {
        Document doc = mongoClient.getDatabase(dbName)
                .getCollection("users")
                .find(new Document("email", email))
                .first();
        if (doc == null) return null;
        return doc.getObjectId("avatarFileId");
    }
}
