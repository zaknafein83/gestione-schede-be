#!/usr/bin/env python3
"""
Traduce gli incantesimi SRD 5.1 dal catalogo EN al file IT
(backend/src/main/resources/srd-spells.it.json).

Sorgente: backend/src/main/resources/srd-spells.json (319 spell SRD 5.1, CC-BY-4.0)
Output:   backend/src/main/resources/srd-spells.it.json (idempotente, riprendibile)

Usa l'API Anthropic Claude Haiku per la traduzione (qualità/costo ottimo per testo
tecnico breve). Stima costo per i ~250 spell senza descrizione IT: circa $1-2.

USO:
    pip install anthropic
    export ANTHROPIC_API_KEY=sk-ant-...      # PowerShell: $env:ANTHROPIC_API_KEY="..."
    python tools/translate_spells.py

OPZIONI:
    --only-missing       Traduce solo gli spell senza description IT (default)
    --force              Ritraduce anche quelli già presenti
    --slugs srd:fireball,srd:cure-wounds   Solo questi
    --limit N            Massimo N spell per esecuzione (utile per test)
    --dry-run            Stampa i prompt ma non chiama l'API e non scrive il file
    --model MODEL        Default: claude-haiku-4-5-20251001
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

try:
    from anthropic import Anthropic
except ImportError:
    sys.exit("ERRORE: pip install anthropic")

ROOT      = Path(__file__).resolve().parent.parent
EN_PATH   = ROOT / "backend" / "src" / "main" / "resources" / "srd-spells.json"
IT_PATH   = ROOT / "backend" / "src" / "main" / "resources" / "srd-spells.it.json"

# Modello di default: Haiku 4.5 — ottimo rapporto qualità/costo per traduzione tecnica.
DEFAULT_MODEL = "claude-haiku-4-5-20251001"

SYSTEM_PROMPT = """Sei un traduttore esperto di Dungeons & Dragons 5e dall'inglese all'italiano.
Stai traducendo gli incantesimi del System Reference Document 5.1 (SRD), contenuto OPEN
rilasciato da Wizards of the Coast sotto licenza CC-BY-4.0.

REGOLE INDEROGABILI:
- NON copiare dal manuale italiano ufficiale (Asmodee/WotC) — il tuo testo deve essere
  un'opera originale derivata dall'SRD inglese.
- Usa terminologia D&D italiana standard delle community (NON delle traduzioni ufficiali):
  * "saving throw" → "tiro salvezza" (TS)
  * "ability check" → "prova di caratteristica"
  * "spell attack" → "attacco con incantesimo"
  * "ranged spell attack" → "attacco con incantesimo a distanza"
  * "melee spell attack" → "attacco con incantesimo in mischia"
  * "hit points" → "punti ferita" (PF)
  * "armor class" → "classe armatura" (CA)
  * "hit dice" → "dadi vita"
  * "bonus action" → "azione bonus"
  * "reaction" → "reazione"
  * Schools: abjuration→abiurazione, conjuration→evocazione, divination→divinazione,
    enchantment→ammaliamento, evocation→invocazione, illusion→illusione,
    necromancy→necromanzia, transmutation→trasmutazione
- Distanze SEMPRE in metri: 5 ft = 1,5 m, 30 ft = 9 m, 60 ft = 18 m, 90 ft = 27 m,
  120 ft = 36 m, 150 ft = 45 m, 300 ft = 90 m, 500 ft = 150 m, 1 mile = 1,6 km.
- Mantieni grassetto/corsivi se presenti come Markdown.
- Mantieni le interruzioni di paragrafo (\\n\\n) dell'originale.
- Stile chiaro e funzionale, non aulico.

OUTPUT: SOLO un oggetto JSON valido con le chiavi (omettere quelle non applicabili):
  - "name": stringa, nome italiano dell'incantesimo
  - "description": stringa, descrizione tradotta (preserva i paragrafi)
  - "atHigherLevels": stringa, sezione "A livelli superiori" (se presente nell'originale)
  - "materialDescription": stringa, descrizione dei componenti materiali (se presente)

NON aggiungere markdown code fences, commenti o testo fuori dal JSON."""

USER_TEMPLATE = """Traduci in italiano questo incantesimo SRD 5.1.

ID: {id}
Nome EN: {name}
Livello: {level} ({level_label})
Scuola: {school}

DESCRIPTION (EN):
{description}

AT HIGHER LEVELS (EN):
{atHigherLevels}

MATERIAL COMPONENT (EN):
{materialDescription}

Restituisci SOLO il JSON con i campi tradotti che applicano (name, description,
atHigherLevels, materialDescription). Salta i campi non presenti nell'originale."""


def load_json(path: Path):
    if not path.exists():
        return {}
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def reorder_translation(d: dict) -> dict:
    """Stabilisce un ordine canonico per le chiavi di una entry IT."""
    out = {}
    for k in ("name", "school", "castingTime", "range", "duration",
              "description", "atHigherLevels", "materialDescription"):
        if k in d:
            out[k] = d[k]
    return out


def save_it_file(path: Path, data: dict) -> None:
    """Scrive il JSON IT con un layout leggibile (una entry per riga)."""
    data = {slug: reorder_translation(entry) for slug, entry in data.items()}
    with path.open("w", encoding="utf-8") as f:
        f.write("{\n")
        items = list(data.items())
        for i, (slug, tr) in enumerate(items):
            line = f'  "{slug}": ' + json.dumps(tr, ensure_ascii=False)
            f.write(line + (",\n" if i < len(items) - 1 else "\n"))
        f.write("}\n")


def needs_translation(it_entry: dict, en_spell: dict) -> bool:
    """True se la entry IT manca della description o dei campi essenziali."""
    if not it_entry.get("description"):
        return True
    if en_spell.get("atHigherLevels") and not it_entry.get("atHigherLevels"):
        return True
    return False


def call_claude(client: Anthropic, model: str, spell: dict, dry_run: bool) -> dict:
    level = spell.get("level", 0)
    level_label = "trucchetto" if level == 0 else f"livello {level}"
    user_msg = USER_TEMPLATE.format(
        id=spell["id"],
        name=spell.get("name", ""),
        level=level,
        level_label=level_label,
        school=spell.get("school", ""),
        description=spell.get("description") or "(none)",
        atHigherLevels=spell.get("atHigherLevels") or "(none)",
        materialDescription=(spell.get("components") or {}).get("materialDescription") or "(none)",
    )
    if dry_run:
        print(f"--- DRY-RUN prompt for {spell['id']} ---")
        print(user_msg[:400] + ("..." if len(user_msg) > 400 else ""))
        return {}

    resp = client.messages.create(
        model=model,
        max_tokens=2048,
        system=SYSTEM_PROMPT,
        messages=[{"role": "user", "content": user_msg}],
    )
    text = resp.content[0].text.strip()
    # Defensive cleanup: rimuovi code fences se il modello le mette comunque
    if text.startswith("```"):
        text = text.split("```", 2)[1]
        if text.startswith("json"):
            text = text[4:]
        text = text.strip().rstrip("`").strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError as e:
        print(f"  ERRORE JSON per {spell['id']}: {e}")
        print(f"  Risposta: {text[:200]}...")
        return {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only-missing", action="store_true", default=True,
                    help="Solo spell senza description IT (default)")
    ap.add_argument("--force", action="store_true",
                    help="Ritraduce anche spell già presenti")
    ap.add_argument("--slugs", help="Lista di slug (csv), es. srd:fireball,srd:shield")
    ap.add_argument("--limit", type=int, help="Massimo N spell per esecuzione")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("--save-every", type=int, default=5,
                    help="Salva il file ogni N traduzioni (per ripresa)")
    args = ap.parse_args()

    en_list = load_json(EN_PATH)
    if not isinstance(en_list, list):
        sys.exit(f"ERRORE: {EN_PATH} non è una lista")
    it_data = load_json(IT_PATH) or {}

    en_by_slug = {s["id"]: s for s in en_list}

    # Seleziona gli spell da tradurre
    candidates = list(en_by_slug.keys())
    if args.slugs:
        wanted = {s.strip() for s in args.slugs.split(",") if s.strip()}
        candidates = [c for c in candidates if c in wanted]
    if not args.force:
        candidates = [c for c in candidates if needs_translation(it_data.get(c, {}), en_by_slug[c])]
    if args.limit:
        candidates = candidates[:args.limit]

    if not candidates:
        print("Nessuno spell da tradurre. Tutto già fatto.")
        return

    print(f"Da tradurre: {len(candidates)} spell con modello {args.model}")
    if not args.dry_run:
        if "ANTHROPIC_API_KEY" not in os.environ:
            sys.exit("ERRORE: variabile d'ambiente ANTHROPIC_API_KEY mancante")

    client = Anthropic() if not args.dry_run else None

    done = 0
    started = time.time()
    for slug in candidates:
        spell = en_by_slug[slug]
        print(f"[{done+1}/{len(candidates)}] {slug} — {spell.get('name')}...", end=" ", flush=True)
        try:
            tr = call_claude(client, args.model, spell, args.dry_run)
            if tr:
                if slug not in it_data:
                    it_data[slug] = {}
                # Non sovrascrivere metadati (school/castingTime/range/duration) gia' presenti
                for key in ("name", "description", "atHigherLevels", "materialDescription"):
                    if key in tr and tr[key]:
                        it_data[slug][key] = tr[key]
                print("OK")
            else:
                print("(skip)")
            done += 1
            if not args.dry_run and done % args.save_every == 0:
                save_it_file(IT_PATH, it_data)
                print(f"  --> file salvato ({done}/{len(candidates)})")
        except KeyboardInterrupt:
            print("\nInterrotto. Salvataggio in corso...")
            break
        except Exception as e:
            print(f"FAIL: {e}")

    if not args.dry_run:
        save_it_file(IT_PATH, it_data)
        elapsed = time.time() - started
        print(f"\nFatto: {done} spell tradotti in {elapsed:.0f}s. File: {IT_PATH}")


if __name__ == "__main__":
    main()
