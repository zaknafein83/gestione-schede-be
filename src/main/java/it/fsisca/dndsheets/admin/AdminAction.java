package it.fsisca.dndsheets.admin;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.Map;

/**
 * Voce di audit log per le azioni amministrative. Append-only, non si modifica
 * né si cancella (salvo cascade su delete account dell'admin, valutabile dopo).
 */
@MongoEntity(collection = "admin_actions")
public class AdminAction extends PanacheMongoEntity {

    public static final String ACTION_GRANT_PREMIUM  = "GRANT_PREMIUM";
    public static final String ACTION_REVOKE_PREMIUM = "REVOKE_PREMIUM";
    public static final String ACTION_DELETE_USER    = "DELETE_USER";

    public ObjectId adminId;
    public String adminEmail;          // snapshot per leggibilita' nel log
    public String action;
    public ObjectId targetUserId;
    public String targetEmail;          // snapshot
    public Map<String, Object> payload; // dati extra (es. source del grant)
    public Instant at;
}
