package it.fsisca.dndsheets.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Sniffing dei "magic bytes" di un'immagine. Serve a non fidarsi del
 * Content-Type dichiarato dal client negli upload (un file puo' dichiarare
 * {@code image/png} ma contenere SVG/HTML con payload XSS).
 */
public final class ImageMagic {

    private ImageMagic() {}

    /**
     * Legge i primi byte del file e ritorna il content-type canonico
     * ({@code image/jpeg}, {@code image/png}, {@code image/webp}) in base
     * alla firma reale, oppure {@code null} se non riconosciuto.
     */
    public static String detect(Path file) {
        final byte[] h;
        try (InputStream in = Files.newInputStream(file)) {
            h = in.readNBytes(12);
        } catch (IOException e) {
            return null;
        }

        // JPEG: FF D8 FF
        if (h.length >= 3
                && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (h.length >= 8
                && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                && (h[4] & 0xFF) == 0x0D && (h[5] & 0xFF) == 0x0A
                && (h[6] & 0xFF) == 0x1A && (h[7] & 0xFF) == 0x0A) {
            return "image/png";
        }
        // WebP: "RIFF" .... "WEBP"
        if (h.length >= 12
                && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return "image/webp";
        }
        return null;
    }
}
