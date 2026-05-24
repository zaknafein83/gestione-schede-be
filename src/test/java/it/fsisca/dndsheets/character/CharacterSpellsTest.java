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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Test della spell list su Character: mix di spell-by-reference (spellId
 * del catalogo SRD) e custom homebrew. Verifica che al GET le spell del
 * catalogo siano espanse con tutti i campi PHB.
 */
@QuarkusTest
@DisplayName("Feature SRD spells — spell list su scheda")
class CharacterSpellsTest {

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
    @DisplayName("PATCH spells con spellId catalogo: GET ritorna campi espansi")
    void spellByReferenceExpanded() {
        String access = registerAndLogin("frank@example.com", "frank");
        String id = createCharacter(access, "{\"name\":\"Mage\"}");

        given().header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("""
                      {"spells":[
                        {"spellId":"srd:fireball","prepared":true,"notes":"ranged 150ft"}
                      ]}
                      """)
                .when().patch("/characters/" + id)
                .then().statusCode(200)
                .body("spells", hasSize(1))
                .body("spells[0].spellId",   equalTo("srd:fireball"))
                .body("spells[0].prepared",  is(true))
                .body("spells[0].notes",     equalTo("ranged 150ft"))
                // expansion: campi catalogo popolati nella response
                .body("spells[0].name",      equalTo("Fireball"))
                .body("spells[0].level",     is(3))
                .body("spells[0].school",    equalTo("Evocation"))
                .body("spells[0].castingTime", equalTo("1 action"))
                .body("spells[0].components.verbal", is(true))
                .body("spells[0].description", notNullValue())
                .body("spells[0].source",    equalTo("SRD 5.1"));
    }

    @Test
    @DisplayName("PATCH custom spell (senza spellId): GET ritorna i campi salvati come sono")
    void customSpellPreserved() {
        String access = registerAndLogin("frank@example.com", "frank");
        String id = createCharacter(access, "{\"name\":\"Homebrew\"}");

        given().header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("""
                      {"spells":[{
                        "name":"Soul Whisper",
                        "level":4,
                        "school":"Necromancy",
                        "castingTime":"1 action",
                        "range":"60 feet",
                        "components":{"verbal":true,"somatic":false,"material":true,"materialDescription":"un dente di drago"},
                        "duration":"1 minute",
                        "concentration":true,
                        "ritual":false,
                        "classes":["Wizard"],
                        "description":"Custom homebrew spell.",
                        "prepared":true
                      }]}
                      """)
                .when().patch("/characters/" + id)
                .then().statusCode(200)
                .body("spells", hasSize(1))
                .body("spells[0].spellId",       nullValue())
                .body("spells[0].name",          equalTo("Soul Whisper"))
                .body("spells[0].level",         is(4))
                .body("spells[0].school",        equalTo("Necromancy"))
                .body("spells[0].concentration", is(true))
                .body("spells[0].components.materialDescription", equalTo("un dente di drago"))
                .body("spells[0].prepared",      is(true));
    }

    @Test
    @DisplayName("PATCH con spellId scarta i campi snapshot in storage")
    void referenceSnapshotIsStrippedAtStorage() {
        String access = registerAndLogin("frank@example.com", "frank");
        String id = createCharacter(access, "{\"name\":\"Mage\"}");

        // L'utente manda spell con spellId E i campi snapshot (es. perché
        // li aveva ricevuti nella response): il backend deve salvare solo
        // lo slug + i per-scheda, e l'espansione fa il resto al GET.
        given().header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("""
                      {"spells":[{
                        "spellId":"srd:magic-missile",
                        "name":"FOO_NAME_BOGUS",
                        "level":99,
                        "school":"FOO_SCHOOL",
                        "prepared":false,
                        "notes":"3 dardi"
                      }]}
                      """)
                .when().patch("/characters/" + id)
                .then().statusCode(200);

        // Il GET espande dal catalogo: i campi bogus dell'input sono ignorati
        given().header("Authorization", "Bearer " + access)
                .when().get("/characters/" + id)
                .then().statusCode(200)
                .body("spells[0].spellId", equalTo("srd:magic-missile"))
                .body("spells[0].name",    equalTo("Magic Missile"))
                .body("spells[0].level",   is(1))
                .body("spells[0].school",  equalTo("Evocation"))
                .body("spells[0].notes",   equalTo("3 dardi"));
    }

    @Test
    @DisplayName("Mix di spell catalog + custom in una sola scheda")
    void mixedSpells() {
        String access = registerAndLogin("frank@example.com", "frank");
        String id = createCharacter(access, "{\"name\":\"Mixed\"}");

        given().header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("""
                      {"spells":[
                        {"spellId":"srd:cure-wounds","prepared":true},
                        {"name":"My Custom","level":2,"school":"Illusion","prepared":false}
                      ]}
                      """)
                .when().patch("/characters/" + id)
                .then().statusCode(200)
                .body("spells", hasSize(2))
                .body("spells[0].spellId", equalTo("srd:cure-wounds"))
                .body("spells[0].name",    equalTo("Cure Wounds"))
                .body("spells[0].level",   is(1))
                .body("spells[1].spellId", nullValue())
                .body("spells[1].name",    equalTo("My Custom"))
                .body("spells[1].school",  equalTo("Illusion"));
    }

    @Test
    @DisplayName("Spell con spellId orfano + snapshot: degradata a custom (preserva snapshot)")
    void orphanReferenceDemotedToCustom() {
        String access = registerAndLogin("frank@example.com", "frank");
        String id = createCharacter(access, "{\"name\":\"FromImport\"}");

        // Simula import da un'altra istanza: spellId non in catalogo + snapshot
        given().header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("""
                      {"spells":[{
                        "spellId":"foreign:custom-spell-99",
                        "name":"Foreign Spell",
                        "level":5,
                        "school":"Evocation",
                        "description":"Imported from another DB.",
                        "prepared":true,
                        "notes":"keep me"
                      }]}
                      """)
                .when().patch("/characters/" + id)
                .then().statusCode(200)
                // demoted a custom: spellId rimosso, snapshot mantenuto
                .body("spells[0].spellId",     nullValue())
                .body("spells[0].name",        equalTo("Foreign Spell"))
                .body("spells[0].level",       is(5))
                .body("spells[0].school",      equalTo("Evocation"))
                .body("spells[0].description", equalTo("Imported from another DB."))
                .body("spells[0].notes",       equalTo("keep me"))
                .body("spells[0].prepared",    is(true));
    }

    // ----- helpers -----

    private String createCharacter(String access, String body) {
        return given().header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON).body(body)
                .when().post("/characters").then().statusCode(201)
                .extract().jsonPath().getString("id");
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
