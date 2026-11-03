#!/usr/bin/env python3
"""Fotomontaggio in scala: tre poster A3 sopra un divano da 220 cm, con tre soluzioni di cornice."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ALT = HERE.parent / "alternative"
OUT = ALT / "Mockup_parete_cornici.png"

S = 5                      # pixel per cm
WALL_W, WALL_H = 400, 270  # cm
FLOOR = 12                 # cm di pavimento visibile
F = "/usr/share/fonts/opentype/inter/"
font = lambda w, s: ImageFont.truetype(F + f"Inter-{w}.otf", s)

POSTERS = [ALT / f"Poster_A3_{n}_anteprima.png" for n in
           ("T_tamberi_barshim_scuro", "K1_kimi_20_68", "S_sinner_blu")]

OPTIONS = [
    # titolo, interno cornice (cm), finestra passe-partout (cm) o None, profilo (cm), colore profilo, nota
    ("1 · Cornice A3 senza passe-partout", (29.7, 42.0), None, 1.6, (24, 24, 24),
     "Cornice 29,7×42 · esterno ≈ 33×45 cm · stampa al vivo, nessun bordo"),
    ("2 · Cornice 40×50 con passe-partout A3", (40.0, 50.0), (29.0, 41.0), 2.0, (24, 24, 24),
     "Finestra 29×41 cm · esterno ≈ 44×54 cm · cornice e passe-partout già abbinati"),
    ("3 · Cornice 50×70 con passe-partout A3", (50.0, 70.0), (29.0, 41.0), 2.0, (176, 140, 98),
     "Finestra 29×41 cm · esterno ≈ 54×74 cm · effetto galleria, molto aria attorno"),
]

SOFA_W, SOFA_BACK, SOFA_SEAT = 220, 85, 45
CENTER_Y = 145   # cm da terra, centro dei quadri
GAP = 8          # cm tra le cornici


def cm(x):
    return int(round(x * S))


def panel(title, inner, window, prof, pcol, note):
    W, H = cm(WALL_W), cm(WALL_H + FLOOR)
    im = Image.new("RGB", (W, H), (236, 232, 224))
    d = ImageDraw.Draw(im)
    y0 = lambda h: cm(WALL_H) - cm(h)          # da cm sopra il pavimento a pixel
    d.rectangle([0, cm(WALL_H), W, H], fill=(196, 172, 140))
    d.rectangle([0, cm(WALL_H) - cm(10), W, cm(WALL_H)], fill=(226, 221, 211))  # battiscopa

    # divano (sagoma neutra)
    sx = cm((WALL_W - SOFA_W) / 2) - cm(25)
    col = (120, 128, 122)
    d.rounded_rectangle([sx, y0(SOFA_BACK), sx + cm(SOFA_W), y0(18)], radius=cm(8), fill=col)
    d.rounded_rectangle([sx - cm(4), y0(62), sx + cm(18), y0(10)], radius=cm(6), fill=(108, 116, 110))
    d.rounded_rectangle([sx + cm(SOFA_W - 18), y0(62), sx + cm(SOFA_W + 4), y0(10)], radius=cm(6), fill=(108, 116, 110))
    d.rounded_rectangle([sx + cm(16), y0(SOFA_SEAT), sx + cm(SOFA_W - 16), y0(26)], radius=cm(4), fill=(134, 142, 136))
    for fx in (sx + cm(10), sx + cm(SOFA_W - 14)):
        d.rectangle([fx, y0(10), fx + cm(4), y0(0)], fill=(70, 60, 50))

    # persona 175 cm (sagoma)
    px = sx + cm(SOFA_W) + cm(45)
    pc = (150, 146, 140)
    d.ellipse([px - cm(11), y0(175), px + cm(11), y0(151)], fill=pc)
    d.rounded_rectangle([px - cm(21), y0(148), px + cm(21), y0(88)], radius=cm(10), fill=pc)
    d.rounded_rectangle([px - cm(17), y0(92), px - cm(2), y0(0)], radius=cm(6), fill=pc)
    d.rounded_rectangle([px + cm(2), y0(92), px + cm(17), y0(0)], radius=cm(6), fill=pc)
    d.text((px, y0(178)), "175 cm", font=font("Medium", 26), fill=(110, 106, 100), anchor="md")

    # cornici
    ow, oh = inner[0] + 2 * prof, inner[1] + 2 * prof
    total = 3 * ow + 2 * GAP
    cx_sofa = sx / S + SOFA_W / 2
    left = cx_sofa - total / 2
    top = CENTER_Y + oh / 2
    for i, pth in enumerate(POSTERS):
        x = left + i * (ow + GAP)
        X0, Y0 = cm(x), y0(top)
        # ombra
        sh = Image.new("RGBA", (cm(ow) + 40, cm(oh) + 40), (0, 0, 0, 0))
        ImageDraw.Draw(sh).rectangle([20, 26, cm(ow) + 20, cm(oh) + 26], fill=(0, 0, 0, 55))
        from PIL import ImageFilter
        sh = sh.filter(ImageFilter.GaussianBlur(9))
        im.paste(sh, (X0 - 20, Y0 - 20), sh)
        d.rectangle([X0, Y0, X0 + cm(ow), Y0 + cm(oh)], fill=pcol)
        ix0, iy0 = X0 + cm(prof), Y0 + cm(prof)
        d.rectangle([ix0, iy0, ix0 + cm(inner[0]), iy0 + cm(inner[1])], fill=(248, 246, 241))
        poster = Image.open(pth).convert("RGB")
        if window:
            # A3 dietro la finestra: si vede la parte centrale 29x41
            full = poster.resize((cm(29.7), cm(42.0)), Image.LANCZOS)
            dx, dy = (cm(29.7) - cm(window[0])) // 2, (cm(42.0) - cm(window[1])) // 2
            vis = full.crop((dx, dy, dx + cm(window[0]), dy + cm(window[1])))
            wx = ix0 + (cm(inner[0]) - vis.width) // 2
            wy = iy0 + (cm(inner[1]) - vis.height) // 2
            d.rectangle([wx - 2, wy - 2, wx + vis.width + 1, wy + vis.height + 1], fill=(214, 210, 202))
            im.paste(vis, (wx, wy))
        else:
            im.paste(poster.resize((cm(inner[0]), cm(inner[1])), Image.LANCZOS), (ix0, iy0))

    # quote
    q = (90, 86, 80)
    yq = y0(top) - cm(9)
    d.line([cm(left), yq, cm(left + total), yq], fill=q, width=2)
    for xx in (cm(left), cm(left + total)):
        d.line([xx, yq - 10, xx, yq + 10], fill=q, width=2)
    pct = total / SOFA_W * 100
    d.text((cm(left + total / 2), yq - 8), f"{total:.0f} cm  ·  {pct:.0f}% della larghezza del divano",
           font=font("Medium", 26), fill=q, anchor="md")
    ys = y0(SOFA_BACK) + cm(SOFA_BACK - 30)
    d.text((sx + cm(SOFA_W) / 2, y0(8) - 6), "divano 220 cm", font=font("Medium", 24), fill=(235, 235, 230), anchor="md")

    # intestazione
    d.text((cm(8), cm(8)), title, font=font("SemiBold", 40), fill=(30, 32, 30))
    d.text((cm(8), cm(8) + 52), note, font=font("Regular", 27), fill=(90, 92, 88))
    d.text((cm(8), cm(8) + 90), f"Centro dei quadri a {CENTER_Y} cm da terra · {GAP} cm tra le cornici",
           font=font("Regular", 24), fill=(120, 122, 118))
    return im


panels = [panel(*o) for o in OPTIONS]
W = panels[0].width
sheet = Image.new("RGB", (W, sum(p.height for p in panels) + 20 * (len(panels) - 1)), (255, 255, 255))
y = 0
for p in panels:
    sheet.paste(p, (0, y)); y += p.height + 20
sheet.save(OUT, optimize=True)
print(OUT, sheet.size)
