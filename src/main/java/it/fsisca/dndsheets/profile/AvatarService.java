package it.fsisca.dndsheets.profile;

import com.mongodb.client.MongoClient;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.GridFSDownloadStream;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import it.fsisca.dndsheets.auth.User;
import it.fsisca.dndsheets.common.AppException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

/**
 * Gestione dell'avatar utente su GridFS.
 *
 * Convenzioni:
 *  - Bucket GridFS standard ({@code fs.files} / {@code fs.chunks}).
 *  - Nel documento {@code fs.files.metadata} salviamo {@code contentType}.
 *  - Massimo 2 MiB per immagine; tipi accettati: image/jpeg, image/png, image/webp.
 *  - Caricare un nuovo avatar cancella automaticamente quello precedente.
 */
@ApplicationScoped
public class AvatarService {

    private static final Logger LOG = Logger.getLogger(AvatarService.class);

    private static final long  MAX_BYTES = 2L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    @Inject MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String dbName;

    private GridFSBucket bucket() {
        return GridFSBuckets.create(mongoClient.getDatabase(dbName));
    }

    /**
     * Salva un nuovo avatar e collega il fileId all'utente.
     * Se l'utente aveva gia' un avatar, quello vecchio viene rimosso da GridFS.
     */
    public ObjectId upload(User user, Path filePath, String contentType, long size, String originalName) {
        validate(contentType, size);

        ObjectId previous = user.avatarFileId;

        try (InputStream in = Files.newInputStream(filePath)) {
            GridFSUploadOptions opts = new GridFSUploadOptions()
                    .metadata(new Document()
                            .append("contentType", contentType)
                            .append("userId", user.id));
            ObjectId fileId = bucket().uploadFromStream(
                    originalName == null ? "avatar" : originalName, in, opts);

            user.avatarFileId = fileId;
            user.updatedAt    = Instant.now();
            user.update();

            if (previous != null) {
                deleteSilently(previous);
            }
            LOG.infof("Avatar caricato per %s (fileId=%s, %d bytes)", user.email, fileId, size);
            return fileId;
        } catch (IOException e) {
            throw new RuntimeException("Errore lettura file upload", e);
        }
    }

    /** Apre uno stream di download dell'avatar; lancia 404 se non c'e'. */
    public AvatarDownload download(User user) {
        if (user.avatarFileId == null) {
            throw AppException.notFound("AVATAR_NOT_FOUND", "Nessun avatar caricato");
        }
        GridFSDownloadStream stream = bucket().openDownloadStream(user.avatarFileId);
        GridFSFile file = stream.getGridFSFile();
        String contentType = "application/octet-stream";
        if (file.getMetadata() != null && file.getMetadata().getString("contentType") != null) {
            contentType = file.getMetadata().getString("contentType");
        }
        return new AvatarDownload(stream, contentType, file.getLength());
    }

    /** Cancella l'avatar dell'utente. Idempotente. */
    public void delete(User user) {
        if (user.avatarFileId == null) return;
        ObjectId id = user.avatarFileId;
        user.avatarFileId = null;
        user.updatedAt    = Instant.now();
        user.update();
        deleteSilently(id);
    }

    // ----- helpers -----

    private void validate(String contentType, long size) {
        if (size <= 0) {
            throw AppException.badRequest("EMPTY_FILE", "File vuoto");
        }
        if (size > MAX_BYTES) {
            throw AppException.badRequest("FILE_TOO_LARGE",
                    "L'immagine supera la dimensione massima di 2 MiB");
        }
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            throw AppException.badRequest("UNSUPPORTED_MEDIA_TYPE",
                    "Tipo non supportato. Usa JPEG, PNG o WebP");
        }
    }

    private void deleteSilently(ObjectId fileId) {
        try {
            bucket().delete(fileId);
        } catch (Exception e) {
            LOG.warnf(e, "Cancellazione GridFS fallita per fileId=%s", fileId);
        }
    }

    /** Tipo "DTO" interno per restituire il binario + metadata al resource. */
    public record AvatarDownload(InputStream stream, String contentType, long length) {}
}
