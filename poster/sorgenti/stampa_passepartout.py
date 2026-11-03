#!/usr/bin/env python3
"""File definitivi per cornice 40x50 con passe-partout A3 (finestra 29x41 cm).

Parte dalle versioni inglesi e allarga i margini interni di Kimi e Tamberi
da 10 a 14 mm, perché il passe-partout copre circa 3,5 mm ai lati e 5 mm sopra/sotto.
"""
from pathlib import Path

SRC = Path(__file__).resolve().parent

MARGINS = [
    ("left:10mm", "left:14mm"),
    ("right:10mm", "right:14mm"),
    ("width:277mm", "width:269mm"),
    ('data-fit="277"', 'data-fit="269"'),
]
JOBS = {
    # sorgente EN -> nome del file di stampa
    "K1_kimi_20_68": ("Antonelli", MARGINS + [
        (".numK { top:8mm;", ".numK { top:12mm;"),
        (".numL { left:9mm; }", ".numL { left:14mm; }"),
        (".numR { right:9mm;", ".numR { right:14mm;"),
    ]),
    "T_tamberi_barshim_scuro": ("Tamberi_Barshim", MARGINS + [
        (".mastT { left:14mm; top:9mm;", ".mastT { left:14mm; top:12mm;"),
    ]),
    "S_sinner_blu": ("Sinner", []),   # margini già a 14 mm
}

for src, (name, reps) in JOBS.items():
    s = (SRC / f"alt_EN_{src}.html").read_text(encoding="utf-8")
    for a, b in reps:
        if (a, b) in MARGINS:          # margini generici: solo se presenti
            s = s.replace(a, b)
            continue
        assert a in s, f"{src}: non trovato {a!r}"
        s = s.replace(a, b)
    (SRC / f"alt_STAMPA_{name}.html").write_text(s, encoding="utf-8")
    print("scritto", f"alt_STAMPA_{name}.html")
