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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Test endpoint {@code GET /me/export} (GDPR art. 15 + art. 20).
 */
@QuarkusTest
@DisplayName("GET /me/export — GDPR data portability")
class ExportTest {

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
        for (String c : List.of("users", "email_verifications", "refresh_tokens",
                "characters", "share_tokens", "dice_rolls")) {
            db.getCollection(c).deleteMany(new Document());
        }
        mailbox.clear();
    }

    @Test
    @DisplayName("export: utente senza schede → JSON valido con account, characters=[], diceRolls=[], shareTokens=[]")
    void exportEmptyAccount() {
        String access = registerAndLogin("frank@example.com", "frank");

        given()
                .auth().oauth2(access)
                .when().get("/me/export")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .header("Content-Disposition", containsString("attachment"))
                .header("Content-Disposition", containsString("pg5e-export-"))
                .body("_meta.generatedAt",        notNullValue())
                .body("_meta.schemaVersion",      equalTo("1"))
                .body("account.email",            equalTo("frank@example.com"))
                .body("account.username",         equalTo("frank"))
                .body("account.tier",             equalTo("FREE"))
                .body("account.emailVerified",    is(true))
                .body("account.hasAvatar",        is(false))
                .body("account.privacyAcceptedAt", notNullValue())
                .body("account.ageDeclaredAt",    notNullValue())
                .body("$",                        not(hasKey("passwordHash")))
                .body("account",                  not(hasKey("passwordHash")))
                .body("characters",               hasSize(0))
                .body("diceRolls",                hasSize(0))
                .body("shareTokens",              hasSize(0));
    }

    @Test
    @DisplayName("export: utente con scheda → la scheda compare nella sezione characters")
    void exportWithCharacter() {
        String access = registerAndLogin("frank@example.com", "frank");

        // Crea una scheda
        given()
                .auth().oauth2(access)
                .contentType(ContentType.JSON)
                .body("""
                      {"name":"Bilbo","race":"Halfling","className":"Rogue","level":3}
                      """)
                .when().post("/characters")
                .then().statusCode(201);

        given()
                .auth().oauth2(access)
                .when().get("/me/export")
                .then()
                .statusCode(200)
                .body("characters",            hasSize(1))
                .body("characters[0].name",    equalTo("Bilbo"))
                .body("characters[0].race",    equalTo("Halfling"))
                .body("characters[0].className", equalTo("Rogue"))
                .body("characters[0].level",   equalTo(3))
                .body("characters[0].hasPortrait", is(false));
    }

    @Test
    @DisplayName("export: senza JWT → 401")
    void exportUnauthorized() {
        given()
                .when().get("/me/export")
                .then().statusCode(401);
    }

    // ==================================================================
    //                          helpers
    // ==================================================================

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
        if (!m.find()) throw new AssertionError("Token non trovato in body: " + body);
        return m.group(1);
    }

    // RestAssured helpers
    private static org.hamcrest.Matcher<java.util.Map<?, ?>> hasKey(String key) {
        return org.hamcrest.Matchers.hasKey(key);
    }
    private static <T> org.hamcrest.Matcher<T> not(org.hamcrest.Matcher<T> m) {
        return org.hamcrest.Matchers.not(m);
    }
}
