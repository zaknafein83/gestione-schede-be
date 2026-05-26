package it.fsisca.dndsheets.character;

import it.fsisca.dndsheets.auth.User;
import it.fsisca.dndsheets.character.dto.CharacterLayoutPayload;
import it.fsisca.dndsheets.character.model.LayoutWidget;
import it.fsisca.dndsheets.common.AppException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;

/**
 * Logica per il layout della dashboard di una scheda. Operazioni:
 * GET (libero), PUT (premium-only), DELETE (libero — reset a default).
 * Ownership: passa per {@link CharacterService#get} che restituisce 404 se
 * la scheda non appartiene all'owner.
 */
@ApplicationScoped
public class CharacterLayoutService {

    @Inject CharacterService characterService;

    /** Ritorna il layout custom o {@link Optional#empty()} se nessuno. */
    public Optional<CharacterLayout> findFor(User owner, String characterId) {
        Character c = characterService.get(owner, characterId);
        Optional<CharacterLayout> opt = CharacterLayout
                .<CharacterLayout>find("ownerId = ?1 and characterId = ?2", owner.id, c.id)
                .firstResultOptional();
        opt.ifPresent(this::migrateInjectSavesIfNeeded);
        return opt;
    }

    /**
     * Migrazione one-shot per lo split del widget "Abilità" in "Abilità" (solo
     * skill) + "Tiri salvezza" (solo TS). Se il layout salvato ha un widget
     * ABILITIES ma nessun SAVES, aggiungiamo SAVES e persistiamo cosi' la
     * prossima volta la condizione non scatta piu' e le modifiche dell'utente
     * (es. rimuovere SAVES volontariamente) sono preservate.
     *
     * <p>Posizionamento: x=0, y=0, default 16x12, z = maxZ+1 — finisce in alto a
     * sinistra sopra gli altri widget; l'utente lo riposiziona dall'editor.</p>
     */
    private void migrateInjectSavesIfNeeded(CharacterLayout l) {
        if (l.widgets == null || l.widgets.isEmpty()) return;
        boolean hasAbilities = l.widgets.stream().anyMatch(w -> "ABILITIES".equals(w.type));
        boolean hasSaves     = l.widgets.stream().anyMatch(w -> "SAVES".equals(w.type));
        if (!hasAbilities || hasSaves) return;

        int maxZ = l.widgets.stream().mapToInt(w -> w.z).max().orElse(0);
        LayoutWidget saves = new LayoutWidget();
        saves.type = "SAVES";
        saves.x = 0;
        saves.y = 0;
        saves.w = 16;
        saves.h = 12;
        saves.z = maxZ + 1;
        l.widgets.add(saves);
        l.updatedAt = Instant.now();
        l.update();
    }

    /**
     * Salva il layout (upsert: crea se non esiste, sostituisce widget se esiste).
     * Solo utenti PREMIUM (o ADMIN) possono salvare. FREE → 402 TIER_LIMIT_REACHED.
     */
    public CharacterLayout save(User owner, String characterId, CharacterLayoutPayload payload) {
        enforcePremium(owner);
        Character c = characterService.get(owner, characterId);

        validateWidgets(payload);

        Optional<CharacterLayout> existing = CharacterLayout
                .<CharacterLayout>find("ownerId = ?1 and characterId = ?2", owner.id, c.id)
                .firstResultOptional();

        Instant now = Instant.now();
        CharacterLayout l;
        if (existing.isPresent()) {
            l = existing.get();
            l.widgets = toWidgets(payload);
            l.updatedAt = now;
            if (payload.version() != null) l.version = payload.version();
            l.update();
        } else {
            l = new CharacterLayout();
            l.ownerId = owner.id;
            l.characterId = c.id;
            l.widgets = toWidgets(payload);
            l.version = payload.version() != null ? payload.version() : 1;
            l.createdAt = now;
            l.updatedAt = now;
            l.persist();
        }
        return l;
    }

    /** Cancella il layout custom (idempotente). */
    public void reset(User owner, String characterId) {
        Character c = characterService.get(owner, characterId);
        CharacterLayout
                .<CharacterLayout>find("ownerId = ?1 and characterId = ?2", owner.id, c.id)
                .firstResultOptional()
                .ifPresent(CharacterLayout::delete);
    }

    // ----- helpers -----

    private void enforcePremium(User owner) {
        if (owner.isPremium() || owner.isAdmin()) return;
        throw AppException.paymentRequired("TIER_LIMIT_REACHED",
                "Il layout personalizzato è una funzionalità Premium. Passa a Premium per personalizzare la dashboard.");
    }

    private void validateWidgets(CharacterLayoutPayload payload) {
        var seenTypes = new java.util.HashSet<String>();
        for (var w : payload.widgets()) {
            if (!LayoutWidget.VALID_TYPES.contains(w.type())) {
                throw AppException.badRequest("INVALID_WIDGET_TYPE",
                        "Tipo widget non riconosciuto: " + w.type());
            }
            // MVP: una sola istanza per tipo.
            if (!seenTypes.add(w.type())) {
                throw AppException.badRequest("DUPLICATE_WIDGET_TYPE",
                        "Widget duplicato nel layout: " + w.type());
            }
        }
    }

    private java.util.List<LayoutWidget> toWidgets(CharacterLayoutPayload payload) {
        java.util.List<LayoutWidget> out = new ArrayList<>(payload.widgets().size());
        for (var p : payload.widgets()) {
            LayoutWidget w = new LayoutWidget();
            w.type       = p.type();
            w.x          = p.x();
            w.y          = p.y();
            w.w          = p.w();
            w.h          = p.h();
            w.z          = p.z();
            w.configJson = p.configJson();
            out.add(w);
        }
        return out;
    }
}
