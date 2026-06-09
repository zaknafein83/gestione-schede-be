package it.fsisca.dndsheets.character;

import com.mongodb.client.MongoClient;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.GridFSDownloadStream;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import it.fsisca.dndsheets.common.AppException;
import it.fsisca.dndsheets.common.ImageMagic;
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
 * Ritratto della scheda personaggio su GridFS. Stesso bucket {@code fs.files}
 * usato per gli avatar utente: distinzione tramite {@code metadata.kind} =
 * {@code "characterPortrait"} (gli avatar non valorizzano questo campo).
 * <p>
 * Caricare un nuovo ritratto cancella automaticamente il precedente.
 */
@ApplicationScoped
public class CharacterPortraitService {

    private static final Logger LOG = Logger.getLogger(CharacterPortraitService.class);

    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    @Inject MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String dbName;

    private GridFSBucket bucket() {
        return GridFSBuckets.create(mongoClient.getDatabase(dbName));
    }

    /**
     * Salva un nuovo ritratto e collega il fileId alla scheda. Se la scheda
     * aveva gia' un ritratto, quello vecchio viene rimosso da GridFS.
     */
    public ObjectId upload(Character ch, Path filePath, String contentType, long size, String originalName) {
        validate(contentType, size, filePath);

        ObjectId previous = ch.portraitFileId;

        try (InputStream in = Files.newInputStream(filePath)) {
            GridFSUploadOptions opts = new GridFSUploadOptions()
                    .metadata(new Document()
                            .append("contentType", contentType)
                            .append("kind", "characterPortrait")
                            .append("characterId", ch.id)
                            .append("ownerId", ch.ownerId));
            ObjectId fileId = bucket().uploadFromStream(
                    originalName == null ? "portrait" : originalName, in, opts);

            ch.portraitFileId = fileId;
            ch.updatedAt      = Instant.now();
            ch.update();

            if (previous != null) {
                deleteSilently(previous);
            }
            LOG.infof("Portrait caricato per scheda %s (fileId=%s, %d bytes)", ch.id, fileId, size);
            return fileId;
        } catch (IOException e) {
            throw new RuntimeException("Errore lettura file upload", e);
        }
    }

    /** Apre uno stream di download del ritratto; 404 se non c'e'. */
    public PortraitDownload download(Character ch) {
        if (ch.portraitFileId == null) {
            throw AppException.notFound("PORTRAIT_NOT_FOUND", "Nessun ritratto sulla scheda");
        }
        GridFSDownloadStream stream = bucket().openDownloadStream(ch.portraitFileId);
        GridFSFile file = stream.getGridFSFile();
        String contentType = "application/octet-stream";
        if (file.getMetadata() != null && file.getMetadata().getString("contentType") != null) {
            contentType = file.getMetadata().getString("contentType");
        }
        return new PortraitDownload(stream, contentType, file.getLength());
    }

    /** Cancella il ritratto della scheda. Idempotente. */
    public void delete(Character ch) {
        if (ch.portraitFileId == null) return;
        ObjectId id = ch.portraitFileId;
        ch.portraitFileId = null;
        ch.updatedAt      = Instant.now();
        ch.update();
        deleteSilently(id);
    }

    /**
     * Cancella solo il file GridFS, senza toccare alcuna entity. Usato
     * dopo la rimozione di una Character per ripulire il ritratto orfano.
     * Idempotente.
     */
    public void deleteFileOnly(ObjectId fileId) {
        if (fileId == null) return;
        deleteSilently(fileId);
    }

    /**
     * Clona un file portrait esistente in un nuovo file GridFS associato a
     * {@code newOwnerCharacter}. Usato dal duplicate. Ritorna l'id del nuovo
     * file, oppure null se {@code sourceFileId} e' null.
     */
    public ObjectId cloneFile(ObjectId sourceFileId, Character newOwnerCharacter) {
        if (sourceFileId == null) return null;
        GridFSDownloadStream source = bucket().openDownloadStream(sourceFileId);
        GridFSFile file = source.getGridFSFile();
        String contentType = "application/octet-stream";
        if (file.getMetadata() != null && file.getMetadata().getString("contentType") != null) {
            contentType = file.getMetadata().getString("contentType");
        }
        try (source) {
            GridFSUploadOptions opts = new GridFSUploadOptions()
                    .metadata(new Document()
                            .append("contentType", contentType)
                            .append("kind", "characterPortrait")
                            .append("characterId", newOwnerCharacter.id)
                            .append("ownerId", newOwnerCharacter.ownerId));
            return bucket().uploadFromStream(file.getFilename(), source, opts);
        }
    }

    // ----- helpers -----

    private void validate(String contentType, long size, Path filePath) {
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
        // Non fidarsi del Content-Type dichiarato: verifica i magic bytes.
        String sniffed = ImageMagic.detect(filePath);
        if (sniffed == null || !ALLOWED_TYPES.contains(sniffed)) {
            throw AppException.badRequest("UNSUPPORTED_MEDIA_TYPE",
                    "Il contenuto del file non e' un'immagine JPEG, PNG o WebP valida");
        }
    }

    private void deleteSilently(ObjectId fileId) {
        try {
            bucket().delete(fileId);
        } catch (Exception e) {
            LOG.warnf(e, "Cancellazione GridFS fallita per fileId=%s", fileId);
        }
    }

    public record PortraitDownload(InputStream stream, String contentType, long length) {}
}
