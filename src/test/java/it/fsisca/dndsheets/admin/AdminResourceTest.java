package it.fsisca.dndsheets.admin;

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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Test feature admin — list/grant/revoke/delete utenti.
 */
@QuarkusTest
@DisplayName("Feature admin — gestione utenti")
class AdminResourceTest {

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
        db.getCollection("admin_actions").deleteMany(new Document());
        mailbox.clear();
    }

    // ==================================================================
    //                       AUTHN / AUTHZ
    // ==================================================================

    @Test
    @DisplayName("/admin/users senza token → 401")
    void listWithoutToken() {
        given().when().get("/admin/users").then().statusCode(401);
    }

    @Test
    @DisplayName("/admin/users con token utente normale → 403")
    void listAsNonAdmin() {
        String access = registerAndLogin("frank@example.com", "frank");

        given()
                .header("Authorization", "Bearer " + access)
                .when().get("/admin/users")
                .then().statusCode(403);
    }

    // ==================================================================
    //                       LIST + SEARCH + PAGE
    // ==================================================================

    @Test
    @DisplayName("/admin/users come admin → lista paginata")
    void listAsAdmin() {
        registerAndLogin("frank@example.com", "frank");
        registerAndLogin("john@example.com", "john");
        String adminAccess = registerAndLoginAsAdmin("admin@example.com", "admin");

        given()
                .header("Authorization", "Bearer " + adminAccess)
                .when().get("/admin/users")
                .then()
                .statusCode(200)
                .body("total", greaterThanOrEqualTo(3))
                .body("items.email", hasItem("admin@example.com"));
    }

    @Test
    @DisplayName("/admin/users?q=frank → filtra")
    void listWithQuery() {
        registerAndLogin("frank@example.com", "frank");
        registerAndLogin("john@example.com", "john");
        String adminAccess = registerAndLoginAsAdmin("admin@example.com", "admin");

        given()
                .header("Authorization", "Bearer " + adminAccess)
                .queryParam("q", "frank")
                .when().get("/admin/users")
                .then()
                .statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].email", equalTo("frank@example.com"));
    }

    @Test
    @DisplayName("/admin/users?page=0&pageSize=1 → rispetta page size")
    void listPaginated() {
        registerAndLogin("frank@example.com", "frank");
        registerAndLogin("john@example.com", "john");
        String adminAccess = registerAndLoginAsAdmin("admin@example.com", "admin");

        given()
                .header("Authorization", "Bearer " + adminAccess)
                .queryParam("page",     "0")
                .queryParam("pageSize", "1")
                .when().get("/admin/users")
                .then()
                .statusCode(200)
                .body("items", hasSize(1))
                .body("pageSize", is(1));
    }

    // ==================================================================
    //                       GRANT / REVOKE PREMIUM
    // ==================================================================

    @Test
    @DisplayName("Grant premium → tier=PREMIUM, source=ADMIN_GRANT")
    void grantPremium() {
        registerAndLogin("frank@example.com", "frank");
        String frankId = userIdByEmail("frank@example.com");
        String adminAccess = registerAndLoginAsAdmin("admin@example.com", "admin");

        given()
                .header("Authorization", "Bearer " + adminAccess)
                .when().post("/admin/users/" + frankId + "/grant-premium")
                .then()
                .statusCode(200)
                .body("tier",          equalTo("PREMIUM"))
                .body("premiumSource", equalTo("ADMIN_GRANT"))
                .body("premiumSince",  notNullValue());

        // Audit log
        long actions = mongoClient.getDatabase(dbName)
                .getCollection("admin_actions")
                .countDocuments(Filters.eq("action", "GRANT_PREMIUM"));
        org.junit.jupiter.api.Assertions.assertEquals(1, actions);
    }

    @Test
    @DisplayName("Grant premium idempotente: 2a volta non duplica audit (idempotenza)")
    void grantPremiumIdempotent() {
        registerAndLogin("frank@example.com", "frank");
        String frankId = userIdByEmail("frank@example.com");
        String adminAccess = registerAndLoginAsAdmin("admin@example.com", "admin");

        given().header("Authorization", "Bearer " + adminAccess)
                .when().post("/admin/users/" + frankId + "/grant-premium")
                .then().statusCode(200);
        given().header("Authorization", "Bearer " + adminAccess)
                .when().post("/admin/users/" + frankId + "/grant-premium")
                .then().statusCode(200);

        long actions = mongoClient.getDatabase(dbName)
                .getCollection("admin_actions")
                .countDocuments(Filters.eq("action", "GRANT_PREMIUM"));
        org.junit.jupiter.api.Assertions.assertEquals(1, actions);
    }

    @Test
    @DisplayName("Revoke premium → tier=FREE, premiumSince=null")
    void revokePremium() {
        registerAndLogin("frank@example.com", "frank");
        String frankId = userIdByEmail("frank@example.com");
        String adminAccess = registerAndLoginAsAdmin("admin@example.com", "admin");

        given().header("Authorization", "Bearer " + adminAccess)
                .when().post("/admin/users/" + frankId + "/grant-premium")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + adminAccess)
                .when().post("/admin/users/" + frankId + "/revoke-premium")
                .then()
                .statusCode(200)
                .body("tier", equalTo("FREE"))
                .body("premiumSince",  nullValue())
                .body("premiumSource", nullValue());
    }

    @Test
    @DisplayName("Grant su utente inesistente → 404")
    void grantOnMissingUser() {
        String adminAccess = registerAndLoginAsAdmin("admin@example.com", "admin");

        // ObjectId formalmente valido ma inesistente
        given().header("Authorization", "Bearer " + adminAccess)
                .when().post("/admin/users/0000000000000000deadbeef/grant-premium")
                .then().statusCode(404);
    }

    // ==================================================================
    //                          DELETE USER
    // ==================================================================

    @Test
    @DisplayName("Delete user → 204 + utente sparisce")
    void deleteUser() {
        registerAndLogin("frank@example.com", "frank");
        String frankId = userIdByEmail("frank@example.com");
        String adminAccess = registerAndLoginAsAdmin("admin@example.com", "admin");

        given().header("Authorization", "Bearer " + adminAccess)
                .when().delete("/admin/users/" + frankId)
                .then().statusCode(204);

        // Non c'e' piu' in lista
        given().header("Authorization", "Bearer " + adminAccess)
                .queryParam("q", "frank")
                .when().get("/admin/users")
                .then().statusCode(200).body("items", hasSize(0));
    }

    @Test
    @DisplayName("Admin non puo' cancellare se stesso → 403")
    void adminCannotDeleteSelf() {
        String adminAccess = registerAndLoginAsAdmin("admin@example.com", "admin");
        String adminId = userIdByEmail("admin@example.com");

        given().header("Authorization", "Bearer " + adminAccess)
                .when().delete("/admin/users/" + adminId)
                .then()
                .statusCode(403)
                .body("code", equalTo("CANNOT_DELETE_SELF"));
    }

    // ==================================================================
    //                          helpers
    // ==================================================================

    private String userIdByEmail(String email) {
        Document doc = mongoClient.getDatabase(dbName).getCollection("users")
                .find(Filters.eq("email", email)).first();
        if (doc == null) throw new AssertionError("Utente non trovato: " + email);
        return doc.getObjectId("_id").toHexString();
    }

    private String registerAndLoginAsAdmin(String email, String username) {
        registerAndLogin(email, username);
        // Promuoviamo a admin via DB e ri-loginiamo per ottenere un JWT con group "admin"
        mongoClient.getDatabase(dbName).getCollection("users").updateOne(
                Filters.eq("email", email),
                Updates.set("roles", List.of("ADMIN")));
        return given()
                .contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"password\":\"Password123\"}")
                .when().post("/auth/login")
                .then().statusCode(200)
                .extract().jsonPath().getString("accessToken");
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
}
