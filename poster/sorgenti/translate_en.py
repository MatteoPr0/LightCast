#!/usr/bin/env python3
"""Crea le versioni inglesi (alt_EN_*.html) dei poster della serie a colori."""
from pathlib import Path

SRC = Path(__file__).resolve().parent

COMMON = [
    ('<html lang="it">', '<html lang="en">'),
]
K = [
    ('<b>Città del Messico</b><span class="sep">·</span>1 novembre 2026',
     '<b>Mexico City</b><span class="sep">·</span>1 November 2026'),
    ('<span style="font-weight:300">Campione del mondo 2026</span>',
     '<span style="font-weight:300">World Champion 2026</span>'),
    ('<h2>Il podio</h2>', '<h2>Podium</h2>'),
]
FILES = {
    "K1_kimi_20_68": K + [
        ('Il più giovane campione del mondo di sempre in Formula 1',
         'The youngest world champion in Formula 1 history'),
        ('<div class="serif lab">anni</div>', '<div class="serif lab">years</div>'),
        ('<div class="serif lab">giorni</div>', '<div class="serif lab">days</div>'),
    ],
    "K2_kimi_copertina": K + [
        ('Campione del mondo a 20 anni e 68 giorni', 'World champion at 20 years and 68 days'),
    ],
    "T_tamberi_barshim_scuro": [
        ('data-fit="230">Due ori', 'data-fit="277">Two golds'),
        ('<b>Giochi Olimpici di Tokyo 2020</b><span class="sep">·</span>1 agosto 2021<span class="sep">·</span>Finale del salto in alto maschile',
         '<b>Tokyo 2020 Olympic Games</b><span class="sep">·</span>1 August 2021<span class="sep">·</span>Men’s high jump final'),
        ('«Possiamo avere due ori?»<small>Mutaz Essa Barshim al giudice di gara</small>',
         '“Can we have two golds?”<small>Mutaz Essa Barshim to the competition official</small>'),
        ('<h2>Oro ex aequo</h2>', '<h2>Shared gold</h2>'),
        ('<span class="t">Italia</span>', '<span class="t">Italy</span>'),
        ('2,37 m', '2.37 m'),
    ],
    "S_sinner_blu": [
        ('<b>Finale</b><span class="sep">·</span>28 gennaio 2024',
         '<b>Final</b><span class="sep">·</span>28 January 2024'),
        ('<div class="caps">contro <b>Daniil Medvedev</b></div>',
         '<div class="caps">vs <b>Daniil Medvedev</b></div>'),
        ('Il primo Slam, rimontando da due set sotto.', 'His first Grand Slam, from two sets down.'),
    ],
}

for name, reps in FILES.items():
    s = (SRC / f"alt_{name}.html").read_text(encoding="utf-8")
    for a, b in COMMON + reps:
        assert a in s, f"{name}: non trovato {a[:50]!r}"
        s = s.replace(a, b)
    (SRC / f"alt_EN_{name}.html").write_text(s, encoding="utf-8")
    print("scritto", f"alt_EN_{name}.html")
