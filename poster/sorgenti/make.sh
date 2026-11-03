#!/usr/bin/env bash
# Rigenera i poster di stampa. Requisiti (una volta sola):
#   pip install pillow pypdf pypdfium2
#   npm install playwright && npx playwright install chromium
set -euo pipefail
cd "$(dirname "$0")"
python3 build_alt.py            # tutte le varianti (HTML) + scontorni
python3 translate_en.py         # versioni in inglese
python3 stampa_passepartout.py  # margini per passe-partout A3 (finestra 29x41)
node render_alt.js              # HTML -> PDF con Chromium (in ../alternative)
python3 finalizza.py            # pagina A3 esatta + JPG 300 dpi in ../stampa
