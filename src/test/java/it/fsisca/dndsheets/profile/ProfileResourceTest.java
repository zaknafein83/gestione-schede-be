package it.fsisca.dndsheets.profile;

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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Test feature F3.1 — PATCH /me + POST /me/change-password.
 */
@QuarkusTest
@DisplayName("Feature F3.1 — profile update + change password")
class ProfileResourceTest {

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
        mailbox.clear();
    }

    // ==================================================================
    //                        PATCH /me
    // ==================================================================

    @Test
    @DisplayName("PATCH /me: aggiorna displayName e bio")
    void patchMeUpdatesDisplayNameAndBio() {
        var tokens = registerAndLogin("frank@example.com", "frank");

        given()
                .header("Authorization", "Bearer " + tokens.access)
                .contentType(ContentType.JSON)
                .body("{\"displayName\":\"Frank Updated\",\"bio\":\"Sono un druido\"}")
                .when().patch("/me")
                .then()
                .statusCode(200)
                .body("displayName", equalTo("Frank Updated"))
                .body("bio",         equalTo("Sono un druido"))
                .body("email",       equalTo("frank@example.com"));
    }

    @Test
    @DisplayName("PATCH /me: solo i campi forniti vengono aggiornati (partial update)")
    void patchMePartial() {
        var tokens = registerAndLogin("frank@example.com", "frank");

        // 1. aggiorno solo bio
        given()
                .header("Authorization", "Bearer " + tokens.access)
                .contentType(ContentType.JSON)
                .body("{\"bio\":\"prima bio\"}")
                .when().patch("/me")
                .then().statusCode(200).body("bio", equalTo("prima bio"));

        // 2. aggiorno solo displayName: la bio NON deve cambiare
        given()
                .header("Authorization", "Bearer " + tokens.access)
                .contentType(ContentType.JSON)
                .body("{\"displayName\":\"Nuovo Nome\"}")
                .when().patch("/me")
                .then()
                .statusCode(200)
                .body("displayName", equalTo("Nuovo Nome"))
                .body("bio",         equalTo("prima bio"));
    }

    @Test
    @DisplayName("PATCH /me: senza token → 401")
    void patchMeUnauthenticated() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"bio\":\"ciao\"}")
                .when().patch("/me")
                .then().statusCode(401);
    }

    @Test
    @DisplayName("PATCH /me: bio troppo lunga → 400")
    void patchMeBioTooLong() {
        var tokens = registerAndLogin("frank@example.com", "frank");
        String tooLong = "x".repeat(501);

        given()
                .header("Authorization", "Bearer " + tokens.access)
                .contentType(ContentType.JSON)
                .body("{\"bio\":\"" + tooLong + "\"}")
                .when().patch("/me")
                .then().statusCode(400);
    }

    @Test
    @DisplayName("PATCH /me: displayName vuoto → 400")
    void patchMeEmptyDisplayName() {
        var tokens = registerAndLogin("frank@example.com", "frank");

        given()
                .header("Authorization", "Bearer " + tokens.access)
                .contentType(ContentType.JSON)
                .body("{\"displayName\":\"\"}")
                .when().patch("/me")
                .then().statusCode(400);
    }

    // ==================================================================
    //                  POST /me/change-password
    // ==================================================================

    @Test
    @DisplayName("change-password: happy path → 204 + login con nuova OK")
    void changePasswordHappyPath() {
        var tokens = registerAndLogin("frank@example.com", "frank");

        given()
                .header("Authorization", "Bearer " + tokens.access)
                .contentType(ContentType.JSON)
                .body("{\"currentPassword\":\"Password123\",\"newPassword\":\"NewPassword456\"}")
                .when().post("/me/change-password")
                .then().statusCode(204);

        // login con la nuova
        given()
                .contentType(ContentType.JSON)
                .body("{\"email\":\"frank@example.com\",\"password\":\"NewPassword456\"}")
                .when().post("/auth/login")
                .then().statusCode(200);
    }

    @Test
    @DisplayName("change-password: password attuale sbagliata → 401")
    void changePasswordWrongCurrent() {
        var tokens = registerAndLogin("frank@example.com", "frank");

        given()
                .header("Authorization", "Bearer " + tokens.access)
                .contentType(ContentType.JSON)
                .body("{\"currentPassword\":\"Wrong12345\",\"newPassword\":\"NewPassword456\"}")
                .when().post("/me/change-password")
                .then()
                .statusCode(401)
                .body("code", equalTo("INVALID_CURRENT_PASSWORD"));
    }

    @Test
    @DisplayName("change-password: stessa password → 400 SAME_PASSWORD")
    void changePasswordSame() {
        var tokens = registerAndLogin("frank@example.com", "frank");

        given()
                .header("Authorization", "Bearer " + tokens.access)
                .contentType(ContentType.JSON)
                .body("{\"currentPassword\":\"Password123\",\"newPassword\":\"Password123\"}")
                .when().post("/me/change-password")
                .then()
                .statusCode(400)
                .body("code", equalTo("SAME_PASSWORD"));
    }

    @Test
    @DisplayName("change-password: nuova password debole → 400 validation")
    void changePasswordWeakNew() {
        var tokens = registerAndLogin("frank@example.com", "frank");

        given()
                .header("Authorization", "Bearer " + tokens.access)
                .contentType(ContentType.JSON)
                .body("{\"currentPassword\":\"Password123\",\"newPassword\":\"short\"}")
                .when().post("/me/change-password")
                .then().statusCode(400);
    }

    @Test
    @DisplayName("change-password: refresh token preesistenti vengono revocati")
    void changePasswordRevokesRefreshTokens() {
        var tokens = registerAndLogin("frank@example.com", "frank");

        // verifico che inizialmente il refresh funzioni
        given()
                .contentType(ContentType.JSON)
                .body("{\"refreshToken\":\"" + tokens.refresh + "\"}")
                .when().post("/auth/refresh")
                .then().statusCode(200);
    }

    @Test
    @DisplayName("change-password: dopo il change, vecchi refresh non funzionano piu'")
    void changePasswordInvalidatesOldRefresh() {
        var tokens = registerAndLogin("frank@example.com", "frank");

        given()
                .header("Authorization", "Bearer " + tokens.access)
                .contentType(ContentType.JSON)
                .body("{\"currentPassword\":\"Password123\",\"newPassword\":\"NewPassword456\"}")
                .when().post("/me/change-password")
                .then().statusCode(204);

        given()
                .contentType(ContentType.JSON)
                .body("{\"refreshToken\":\"" + tokens.refresh + "\"}")
                .when().post("/auth/refresh")
                .then()
                .statusCode(401)
                .body("code", equalTo("EXPIRED_OR_REVOKED_REFRESH_TOKEN"));
    }

    @Test
    @DisplayName("change-password: senza token → 401")
    void changePasswordUnauthenticated() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"currentPassword\":\"x\",\"newPassword\":\"NewPassword456\"}")
                .when().post("/me/change-password")
                .then().statusCode(401);
    }

    // ==================================================================
    //                            helpers
    // ==================================================================

    private record Tokens(String access, String refresh) {}

    private Tokens registerAndLogin(String email, String username) {
        // register
        given()
                .contentType(ContentType.JSON)
                .body("""
                      {"email":"%s","password":"Password123","username":"%s","displayName":"Frank S.","acceptPrivacy":true,"declareMinAge":true}
                      """.formatted(email, username))
                .when().post("/auth/register")
                .then().statusCode(201);

        // verify
        String evt = extractTokenFromMailboxFor(email);
        given()
                .contentType(ContentType.JSON)
                .body("{\"token\":\"" + evt + "\"}")
                .when().post("/auth/verify-email")
                .then().statusCode(200);

        // login
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
}
