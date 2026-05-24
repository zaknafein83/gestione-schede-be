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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test feature account deletion: POST /me/delete-account con cascade su
 * tutte le collection dell'utente.
 */
@QuarkusTest
@DisplayName("Feature account deletion")
class DeleteAccountTest {

    private static final Pattern VERIFY_TOKEN = Pattern.compile("verify-email\\?token=([A-Za-z0-9_-]+)");

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
        db.getCollection("password_reset_tokens").deleteMany(new Document());
        db.getCollection("characters").deleteMany(new Document());
        db.getCollection("share_tokens").deleteMany(new Document());
        db.getCollection("dice_rolls").deleteMany(new Document());
        mailbox.clear();
    }

    @Test
    @DisplayName("POST /me/delete-account senza token → 401")
    void deleteAccountUnauthenticated() {
        given().contentType(ContentType.JSON)
                .body("{\"password\":\"Password123\"}")
                .when().post("/me/delete-account")
                .then().statusCode(401);
    }

    @Test
    @DisplayName("POST /me/delete-account con password errata → 401")
    void deleteAccountWrongPassword() {
        String access = registerAndLogin("frank@example.com", "frank");

        given().header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"password\":\"WrongPassword999\"}")
                .when().post("/me/delete-account")
                .then().statusCode(401)
                .body("code", equalTo("INVALID_PASSWORD"));

        // L'utente esiste ancora
        given().header("Authorization", "Bearer " + access)
                .when().get("/me")
                .then().statusCode(200);
    }

    @Test
    @DisplayName("POST /me/delete-account: utente cancellato, login fallisce, email riusabile")
    void deleteAccountHappyPath() {
        String access = registerAndLogin("frank@example.com", "frank");

        given().header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"password\":\"Password123\"}")
                .when().post("/me/delete-account")
                .then().statusCode(204);

        // Vecchio access token su /me: deve fallire perché user non esiste più
        given().header("Authorization", "Bearer " + access)
                .when().get("/me")
                .then().statusCode(401);

        // Login con le stesse credenziali fallisce
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"frank@example.com\",\"password\":\"Password123\"}")
                .when().post("/auth/login")
                .then().statusCode(401);

        // L'email può essere ri-registrata (utente sparito davvero)
        given().contentType(ContentType.JSON)
                .body("""
                      {"email":"frank@example.com","password":"Password123","username":"new_frank","displayName":"New"}
                      """)
                .when().post("/auth/register")
                .then().statusCode(201);
    }

    @Test
    @DisplayName("Delete account cascade: characters, share_tokens, dice_rolls cancellati")
    void deleteAccountCascade() {
        String access = registerAndLogin("frank@example.com", "frank");

        // Crea 2 schede
        String id1 = given().header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON).body("{\"name\":\"A\"}")
                .when().post("/characters").then().statusCode(201)
                .extract().jsonPath().getString("id");
        String id2 = given().header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON).body("{\"name\":\"B\"}")
                .when().post("/characters").then().statusCode(201)
                .extract().jsonPath().getString("id");

        // Crea share token per la prima scheda
        given().header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .when().post("/characters/" + id1 + "/shares")
                .then().statusCode(201);

        // Crea 3 dice rolls
        for (int i = 0; i < 3; i++) {
            given().header("Authorization", "Bearer " + access)
                    .contentType(ContentType.JSON)
                    .body("""
                          {"formula":"1d20","total":15,"breakdown":"[15]","advantage":false,"disadvantage":false,"characterId":"%s"}
                          """.formatted(id1))
                    .when().post("/dice-rolls").then().statusCode(201);
        }

        // Pre-check: i dati esistono
        var db = mongoClient.getDatabase(dbName);
        assertEquals(2, db.getCollection("characters").countDocuments());
        assertEquals(1, db.getCollection("share_tokens").countDocuments());
        assertEquals(3, db.getCollection("dice_rolls").countDocuments());
        // refresh_tokens: 1 da login
        assertEquals(1, db.getCollection("refresh_tokens").countDocuments());

        // Cancella account
        given().header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"password\":\"Password123\"}")
                .when().post("/me/delete-account").then().statusCode(204);

        // Tutto deve essere sparito
        assertEquals(0, db.getCollection("users").countDocuments());
        assertEquals(0, db.getCollection("characters").countDocuments());
        assertEquals(0, db.getCollection("share_tokens").countDocuments());
        assertEquals(0, db.getCollection("dice_rolls").countDocuments());
        assertEquals(0, db.getCollection("refresh_tokens").countDocuments());

        // Le schede non sono più raggiungibili (non che ci fosse modo di farlo:
        // non c'è più nessuno autenticato come quell'utente)
        // Lo share token revocato: link pubblico non funziona più
        // (non possiamo testarlo qui senza salvare il token in chiaro, comunque
        // la cancellazione è coperta dal count == 0)
        // assicuriamoci anche che la prima scheda non sia recuperabile
        // tramite il vecchio token (anche se username è ri-registrabile)
        id1.length();  // suppress unused
        id2.length();
    }

    @Test
    @DisplayName("Delete account: anche password_reset_tokens e email_verifications cancellati")
    void deleteAccountCleansAuthArtifacts() {
        // Registra ma NON verificare → resta email_verifications.
        // Poi registra+verifica frank2 per avere un account "vivo" da cancellare.
        given().contentType(ContentType.JSON)
                .body("""
                      {"email":"frank@example.com","password":"Password123","username":"frank","displayName":"frank"}
                      """)
                .when().post("/auth/register").then().statusCode(201);

        // Verifica e login (consuma il primo evt + emette refresh token)
        String evt = extractVerifyToken("frank@example.com");
        given().contentType(ContentType.JSON)
                .body("{\"token\":\"" + evt + "\"}")
                .when().post("/auth/verify-email").then().statusCode(200);
        String access = given().contentType(ContentType.JSON)
                .body("{\"email\":\"frank@example.com\",\"password\":\"Password123\"}")
                .when().post("/auth/login").then().statusCode(200)
                .extract().jsonPath().getString("accessToken");

        // Avvia un forgot-password (crea un password_reset_token)
        mailbox.clear();
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"frank@example.com\"}")
                .when().post("/auth/forgot-password").then().statusCode(204);

        var db = mongoClient.getDatabase(dbName);
        assertEquals(1L, db.getCollection("password_reset_tokens").countDocuments());
        // email_verifications: la entry creata al register è stata marcata
        // usedAt (non eliminata) → c'è ancora come riga
        org.hamcrest.MatcherAssert.assertThat(
                db.getCollection("email_verifications").countDocuments(),
                greaterThanOrEqualTo(1L));

        // Cancella account
        given().header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"password\":\"Password123\"}")
                .when().post("/me/delete-account").then().statusCode(204);

        // Tutto pulito
        assertEquals(0L, db.getCollection("password_reset_tokens").countDocuments());
        assertEquals(0L, db.getCollection("email_verifications").countDocuments());
    }

    @Test
    @DisplayName("Delete account: lo username torna disponibile")
    void deleteAccountFreesUsername() {
        String access = registerAndLogin("frank@example.com", "frank");

        given().header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{\"password\":\"Password123\"}")
                .when().post("/me/delete-account").then().statusCode(204);

        // Stesso username, email diversa: deve passare
        given().contentType(ContentType.JSON)
                .body("""
                      {"email":"another@example.com","password":"Password123","username":"frank","displayName":"frank"}
                      """)
                .when().post("/auth/register").then().statusCode(201);
    }

    @Test
    @DisplayName("Delete account: body senza password → 400")
    void deleteAccountMissingPassword() {
        String access = registerAndLogin("frank@example.com", "frank");
        given().header("Authorization", "Bearer " + access)
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/me/delete-account")
                .then().statusCode(400);
    }

    @Test
    @DisplayName("Delete account: schede degli altri utenti NON vengono toccate")
    void deleteAccountDoesNotTouchOthers() {
        String access1 = registerAndLogin("frank@example.com", "frank");
        String access2 = registerAndLogin("other@example.com", "other");

        // entrambi creano una scheda
        given().header("Authorization", "Bearer " + access1)
                .contentType(ContentType.JSON).body("{\"name\":\"FrankSheet\"}")
                .when().post("/characters").then().statusCode(201);
        String otherId = given().header("Authorization", "Bearer " + access2)
                .contentType(ContentType.JSON).body("{\"name\":\"OtherSheet\"}")
                .when().post("/characters").then().statusCode(201)
                .extract().jsonPath().getString("id");

        // frank cancella il suo account
        given().header("Authorization", "Bearer " + access1)
                .contentType(ContentType.JSON)
                .body("{\"password\":\"Password123\"}")
                .when().post("/me/delete-account").then().statusCode(204);

        // other esiste ancora e ha ancora la sua scheda
        given().header("Authorization", "Bearer " + access2)
                .when().get("/me").then().statusCode(200);
        given().header("Authorization", "Bearer " + access2)
                .when().get("/characters").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].id", equalTo(otherId));
    }

    // ----- helpers -----

    private String registerAndLogin(String email, String username) {
        given().contentType(ContentType.JSON)
                .body("""
                      {"email":"%s","password":"Password123","username":"%s","displayName":"%s"}
                      """.formatted(email, username, username))
                .when().post("/auth/register").then().statusCode(201);
        String evt = extractVerifyToken(email);
        given().contentType(ContentType.JSON)
                .body("{\"token\":\"" + evt + "\"}")
                .when().post("/auth/verify-email").then().statusCode(200);
        return given().contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"password\":\"Password123\"}")
                .when().post("/auth/login").then().statusCode(200)
                .extract().jsonPath().getString("accessToken");
    }

    private String extractVerifyToken(String email) {
        List<?> msgs = mailbox.getMailMessagesSentTo(email);
        if (msgs.isEmpty()) throw new AssertionError("Nessuna email per " + email);
        for (int i = msgs.size() - 1; i >= 0; i--) {
            String body = mailbox.getMailMessagesSentTo(email).get(i).getText();
            Matcher m = VERIFY_TOKEN.matcher(body);
            if (m.find()) return m.group(1);
        }
        throw new AssertionError("Token verify non trovato per " + email);
    }
}
