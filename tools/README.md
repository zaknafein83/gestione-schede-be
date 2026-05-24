# tools/

Script di utilità del progetto.

## translate_spells.py

Traduce gli incantesimi SRD 5.1 dall'inglese all'italiano usando l'API Claude.

**Setup**:
```powershell
pip install --upgrade anthropic httpx certifi
$env:ANTHROPIC_API_KEY = "sk-ant-..."
python tools/translate_spells.py
```

> Su Python 3.14 fresh può mancare `certifi` (transitiva di `httpx`/`httpcore`):
> se vedi `ModuleNotFoundError: No module named 'certifi'`, lancia il pip sopra.

**Comportamento**:
- Legge `backend/src/main/resources/srd-spells.json` (319 spell SRD)
- Aggiorna `backend/src/main/resources/srd-spells.it.json` incrementalmente
- Idempotente: rilanciandolo, traduce solo gli spell che mancano
- Salva ogni 5 spell (riprendibile se interrotto)
- Default model: `claude-haiku-4-5-20251001` (ottimo rapporto qualità/costo per traduzione tecnica)

**Costo stimato** per i ~250 spell senza descrizione IT al momento: **circa $1-2** in token Haiku.

**Tempo**: ~2-5 minuti totali (chiamate sequenziali, ~1s/spell).

**Opzioni utili**:
```powershell
# Test su 5 spell senza chiamare l'API (vede solo i prompt)
python tools/translate_spells.py --limit 5 --dry-run

# Solo qualche spell specifico
python tools/translate_spells.py --slugs srd:fireball,srd:magic-missile

# Forza ritraduzione di tutti
python tools/translate_spells.py --force
```

Dopo l'esecuzione, riavvia il backend: il seeder ricarica le traduzioni
solo al primo avvio quando la collection è vuota. Per forzare il reload,
elimina la collection `spell_catalog` da Mongo prima del restart:

```powershell
docker exec -it dndsheets-mongo mongosh -u root -p root --authenticationDatabase admin dndsheets --eval "db.spell_catalog.deleteMany({})"
```
