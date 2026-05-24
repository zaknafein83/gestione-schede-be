package it.fsisca.dndsheets.common;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Crea/garantisce gli indici Mongo necessari all'avvio dell'app.
 * Panache non li crea automaticamente. {@code createIndex} e' idempotente.
 */
@Startup
@ApplicationScoped
public class MongoIndexes {

    private static final Logger LOG = Logger.getLogger(MongoIndexes.class);

    @Inject MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String dbName;

    @jakarta.annotation.PostConstruct
    void init() {
        MongoDatabase db = mongoClient.getDatabase(dbName);

        db.getCollection("users").createIndex(
                Indexes.ascending("email"),
                new IndexOptions().unique(true).name("uniq_users_email"));

        db.getCollection("users").createIndex(
                Indexes.ascending("username"),
                new IndexOptions().unique(true).name("uniq_users_username"));

        db.getCollection("email_verifications").createIndex(
                Indexes.ascending("tokenHash"),
                new IndexOptions().unique(true).name("uniq_evt_tokenhash"));

        // TTL: i token scaduti vengono rimossi automaticamente da Mongo
        db.getCollection("email_verifications").createIndex(
                Indexes.ascending("expiresAt"),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("ttl_evt_expires"));

        // Refresh tokens
        db.getCollection("refresh_tokens").createIndex(
                Indexes.ascending("tokenHash"),
                new IndexOptions().unique(true).name("uniq_rt_tokenhash"));
        db.getCollection("refresh_tokens").createIndex(
                Indexes.ascending("userId"),
                new IndexOptions().name("idx_rt_userid"));
        db.getCollection("refresh_tokens").createIndex(
                Indexes.ascending("expiresAt"),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("ttl_rt_expires"));

        // Password reset tokens
        db.getCollection("password_reset_tokens").createIndex(
                Indexes.ascending("tokenHash"),
                new IndexOptions().unique(true).name("uniq_prt_tokenhash"));
        db.getCollection("password_reset_tokens").createIndex(
                Indexes.ascending("userId"),
                new IndexOptions().name("idx_prt_userid"));
        db.getCollection("password_reset_tokens").createIndex(
                Indexes.ascending("expiresAt"),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("ttl_prt_expires"));

        // Characters
        db.getCollection("characters").createIndex(
                Indexes.ascending("ownerId"),
                new IndexOptions().name("idx_chars_ownerid"));

        // Share tokens
        db.getCollection("share_tokens").createIndex(
                Indexes.ascending("tokenHash"),
                new IndexOptions().unique(true).name("uniq_share_tokenhash"));
        db.getCollection("share_tokens").createIndex(
                Indexes.ascending("characterId"),
                new IndexOptions().name("idx_share_charid"));

        // Dice rolls
        db.getCollection("dice_rolls").createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("ownerId"),
                        Indexes.descending("createdAt")),
                new IndexOptions().name("idx_dice_owner_createdAt"));
        db.getCollection("dice_rolls").createIndex(
                Indexes.ascending("expiresAt"),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("ttl_dice_expires"));

        // Spell catalog
        db.getCollection("spell_catalog").createIndex(
                Indexes.ascending("slug"),
                new IndexOptions().unique(true).name("uniq_spellcat_slug"));
        db.getCollection("spell_catalog").createIndex(
                Indexes.ascending("level"),
                new IndexOptions().name("idx_spellcat_level"));
        db.getCollection("spell_catalog").createIndex(
                Indexes.ascending("name"),
                new IndexOptions().name("idx_spellcat_name"));

        // Admin actions audit log
        db.getCollection("admin_actions").createIndex(
                Indexes.descending("at"),
                new IndexOptions().name("idx_admin_actions_at"));
        db.getCollection("admin_actions").createIndex(
                Indexes.ascending("targetUserId"),
                new IndexOptions().name("idx_admin_actions_target"));
        db.getCollection("admin_actions").createIndex(
                Indexes.ascending("adminId"),
                new IndexOptions().name("idx_admin_actions_admin"));

        LOG.info("Indici Mongo verificati/creati");

        // Migration soft: popola tier/roles sugli utenti esistenti.
        // Idempotente — usa filtri "campo mancante" cosi' lascia stare chi e' gia' a posto.
        long tierBackfilled = db.getCollection("users").updateMany(
                Filters.exists("tier", false),
                Updates.set("tier", "FREE")).getModifiedCount();
        long rolesBackfilled = db.getCollection("users").updateMany(
                Filters.exists("roles", false),
                Updates.set("roles", Collections.emptyList())).getModifiedCount();
        if (tierBackfilled > 0 || rolesBackfilled > 0) {
            LOG.infof("Migration utenti: tier popolato su %d doc, roles su %d doc",
                    tierBackfilled, rolesBackfilled);
        }
    }
}
