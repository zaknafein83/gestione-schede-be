package it.fsisca.dndsheets.common;

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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * Verifica che {@link ValidationExceptionMapper} mappi
 * {@link jakarta.validation.ConstraintViolationException} in RFC 7807
 * con {@code code="VALIDATION_FAILED"} e {@code detail} leggibile.
 *
 * <p>Senza questo mapper, Quarkus risponde con solo {@code violations[]} e il
 * frontend cade su "Errore sconosciuto" (vedi bug noto editor scheda).
 */
@QuarkusTest
@DisplayName("ValidationExceptionMapper — RFC 7807 con code/detail leggibili")
class ValidationExceptionMapperTest {

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

    @Test
    @DisplayName("PATCH /characters/{id} con level=25 → 400 VALIDATION_FAILED + detail su 'level'")
    void singleViolationProducesReadableDetail() {
        String access = registerAndLogin("frank@example.com", "frank");
        String id = createCharacter(access, "{\"name\":\"Mirko\"}");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"level\":25}")
                .when().patch("/characters/" + id)
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("code",     equalTo("VALIDATION_FAILED"))
                .body("status",   equalTo(400))
                .body("title",    equalTo("Bad Request"))
                .body("detail",   containsString("level"))
                .body("violations",          hasSize(1))
                .body("violations[0].field", equalTo("level"));
    }

    @Test
    @DisplayName("PATCH /characters/{id} con più violazioni → tutte presenti in violations[]")
    void multipleViolationsAreAllReported() {
        String access = registerAndLogin("frank@example.com", "frank");
        String id = createCharacter(access, "{\"name\":\"Mirko\"}");

        // level=0 (min 1), str=50 (max 30), hpMax=-5 (min 0) → 3 violazioni
        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"level\":0,\"str\":50,\"hpMax\":-5}")
                .when().patch("/characters/" + id)
                .then()
                .statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"))
                .body("violations.field", allOf(
                        hasItem("level"),
                        hasItem("str"),
                        hasItem("hpMax")
                ));
    }

    @Test
    @DisplayName("POST /characters con level=99 → 400 VALIDATION_FAILED (anche su create)")
    void violationOnCreateProducesReadableDetail() {
        String access = registerAndLogin("frank@example.com", "frank");

        given()
                .header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"name\":\"Mirko\",\"level\":99}")
                .when().post("/characters")
                .then()
                .statusCode(400)
                .body("code",   equalTo("VALIDATION_FAILED"))
                .body("detail", containsString("level"))
                .body("violations[0].field", equalTo("level"));
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
        var msgs = mailbox.getMessagesSentTo(email);
        if (msgs.isEmpty()) {
            throw new IllegalStateException("Nessuna mail per " + email);
        }
        String body = msgs.get(0).getHtml() != null ? msgs.get(0).getHtml() : msgs.get(0).getText();
        Matcher m = TOKEN_LINK_PATTERN.matcher(body);
        if (!m.find()) {
            throw new IllegalStateException("Token non trovato nella mail");
        }
        return m.group(1);
    }
}
