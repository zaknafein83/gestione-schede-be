package it.fsisca.dndsheets.spells;

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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Test feature MVP-7 — catalogo incantesimi SRD.
 *
 * Il seeder popola spell_catalog all'avvio dell'app (319 spell SRD 5.1).
 * Questi test verificano gli endpoint /spells contro quel dataset reale.
 */
@QuarkusTest
@DisplayName("Feature SRD spells — catalogo SRD")
class SpellCatalogResourceTest {

    private static final Pattern TOKEN_LINK_PATTERN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Inject MongoClient mongoClient;
    @Inject MockMailbox mailbox;

    @ConfigProperty(name = "quarkus.mongodb.database") String dbName;

    @BeforeAll
    static void registerProblemJsonParser() {
        RestAssured.registerParser("application/problem+json", Parser.JSON);
    }

    @BeforeEach
    void resetUserState() {
        var db = mongoClient.getDatabase(dbName);
        db.getCollection("users").deleteMany(new Document());
        db.getCollection("email_verifications").deleteMany(new Document());
        db.getCollection("refresh_tokens").deleteMany(new Document());
        // NB: NON cancelliamo spell_catalog — è popolata dal seeder al boot
        mailbox.clear();
    }

    @Test
    @DisplayName("GET /spells: senza token → 401")
    void requiresAuth() {
        given().when().get("/spells").then().statusCode(401);
    }

    @Test
    @DisplayName("GET /spells: lista paginata, prima pagina")
    void searchPaginated() {
        String access = registerAndLogin("frank@example.com", "frank");
        given().header("Authorization", "Bearer " + access)
                .when().get("/spells?limit=20")
                .then().statusCode(200)
                .body("$", hasSize(20))
                .body("[0].id",   notNullValue())
                .body("[0].name", notNullValue());
    }

    @Test
    @DisplayName("GET /spells?q=fire: filtra per nome (case-insensitive)")
    void searchByName() {
        String access = registerAndLogin("frank@example.com", "frank");
        given().header("Authorization", "Bearer " + access)
                .when().get("/spells?q=fireball")
                .then().statusCode(200)
                .body("name", hasItem("Fireball"));
    }

    @Test
    @DisplayName("GET /spells?level=0: filtra per livello (trucchetti = 24 SRD)")
    void searchByLevel() {
        String access = registerAndLogin("frank@example.com", "frank");
        given().header("Authorization", "Bearer " + access)
                .when().get("/spells?level=0&limit=100")
                .then().statusCode(200)
                .body("$", hasSize(24))
                .body("level", everyEntryEqualsTo(0));
    }

    @Test
    @DisplayName("GET /spells?school=Evocation: filtra per scuola")
    void searchBySchool() {
        String access = registerAndLogin("frank@example.com", "frank");
        given().header("Authorization", "Bearer " + access)
                .when().get("/spells?school=Evocation&limit=100")
                .then().statusCode(200)
                .body("school", everyEntryEqualsTo("Evocation"));
    }

    @Test
    @DisplayName("GET /spells?className=Wizard: filtra per classe")
    void searchByClass() {
        String access = registerAndLogin("frank@example.com", "frank");
        given().header("Authorization", "Bearer " + access)
                .when().get("/spells?className=Wizard&limit=100")
                .then().statusCode(200)
                .body("$.size()", greaterThan(0))
                .body("[0].classes", hasItem("Wizard"));
    }

    @Test
    @DisplayName("GET /spells/count: numero totale del catalogo SRD")
    void totalCount() {
        String access = registerAndLogin("frank@example.com", "frank");
        given().header("Authorization", "Bearer " + access)
                .when().get("/spells/count")
                .then().statusCode(200)
                .body(is("319"));
    }

    @Test
    @DisplayName("GET /spells/{slug}: dettaglio Fireball completo")
    void detailFireball() {
        String access = registerAndLogin("frank@example.com", "frank");
        given().header("Authorization", "Bearer " + access)
                .when().get("/spells/srd:fireball")
                .then().statusCode(200)
                .body("id",                equalTo("srd:fireball"))
                .body("name",              equalTo("Fireball"))
                .body("level",             is(3))
                .body("school",            equalTo("Evocation"))
                .body("castingTime",       equalTo("1 action"))
                .body("range",             equalTo("150 feet"))
                .body("components.verbal", is(true))
                .body("components.material", is(true))
                .body("components.materialDescription", notNullValue())
                .body("ritual",            is(false))
                .body("concentration",     is(false))
                .body("classes",           hasItem("Wizard"))
                .body("description",       notNullValue())
                .body("atHigherLevels",    notNullValue())
                .body("source",            equalTo("SRD 5.1"));
    }

    @Test
    @DisplayName("GET /spells/{slug}: slug inesistente → 404")
    void detailNotFound() {
        String access = registerAndLogin("frank@example.com", "frank");
        given().header("Authorization", "Bearer " + access)
                .when().get("/spells/srd:does-not-exist")
                .then().statusCode(404)
                .body("code", equalTo("SPELL_NOT_FOUND"));
    }

    // ----- helpers -----

    /** Verifica che ogni elemento della lista JSON sia uguale al valore atteso. */
    private static org.hamcrest.Matcher<?> everyEntryEqualsTo(Object expected) {
        return org.hamcrest.Matchers.everyItem(equalTo(expected));
    }

    private String registerAndLogin(String email, String username) {
        given().contentType(ContentType.JSON)
                .body("""
                      {"email":"%s","password":"Password123","username":"%s","displayName":"%s"}
                      """.formatted(email, username, username))
                .when().post("/auth/register").then().statusCode(201);
        String evt = extractTokenFromMailboxFor(email);
        given().contentType(ContentType.JSON)
                .body("{\"token\":\"" + evt + "\"}")
                .when().post("/auth/verify-email").then().statusCode(200);
        return given().contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"password\":\"Password123\"}")
                .when().post("/auth/login").then().statusCode(200)
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
