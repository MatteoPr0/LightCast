#!/usr/bin/env python3
"""Alternative "da poster sportivo" A3 verticali (297x420 mm).

Le foto restano il JPEG originale (ritagliato via CSS, nessun ricampionamento);
per l'effetto "testo dietro i soggetti" si sovrappone un PNG scontornato
(solo la fascia alta della foto) con la stessa scala e lo stesso registro.
"""
import math
from pathlib import Path
from PIL import Image, ImageOps

SRC = Path(__file__).resolve().parent

HEAD = """<!doctype html><html lang="it"><head><meta charset="utf-8"><title>{title}</title><style>
@font-face {{ font-family:"SerifIt"; src:url("source-serif-4-latin-400-italic.woff2") format("woff2"); font-style:italic; font-weight:400; }}
@font-face {{ font-family:"InterP"; src:url("Inter-Regular.ttf"); font-weight:400; }}
@font-face {{ font-family:"InterP"; src:url("Inter-Medium.ttf"); font-weight:500; }}
@font-face {{ font-family:"InterP"; src:url("Inter-SemiBold.ttf"); font-weight:600; }}
@font-face {{ font-family:"InterDisplayP"; src:url("InterDisplay-Light.ttf"); font-weight:300; }}
@font-face {{ font-family:"InterDisplayP"; src:url("InterDisplay-SemiBold.ttf"); font-weight:600; }}
@font-face {{ font-family:"Shoulders"; src:url("big-shoulders-display-latin-900-normal.woff2") format("woff2"); font-weight:900; }}
@font-face {{ font-family:"Shoulders"; src:url("big-shoulders-display-latin-800-normal.woff2") format("woff2"); font-weight:800; }}
@page {{ size:297mm 420mm; margin:0; }}
* {{ margin:0; padding:0; box-sizing:border-box; }}
html,body {{ width:297mm; height:420mm; overflow:hidden; background:{bg}; color:{fg};
  -webkit-print-color-adjust:exact; print-color-adjust:exact; font-family:"InterP",sans-serif; }}
:root {{ --fg:{fg}; --bg:{bg}; --muted:{muted}; --rule:{rule}; }}
.abs {{ position:absolute; }}
.frame {{ position:absolute; overflow:hidden; }}
.frame img {{ position:absolute; display:block; max-width:none; }}
.caps {{ text-transform:uppercase; letter-spacing:.24em; font-weight:500; line-height:1; white-space:nowrap; color:var(--muted); }}
.caps b {{ color:var(--fg); font-weight:600; }}
.sep {{ padding:0 .55em; }}
.tri {{ display:flex; height:.9mm; }} .tri span {{ flex:1 1 0; }}
.tri .g {{ background:#009246; }} .tri .w {{ background:#F3EFE6; }} .tri .r {{ background:#CE2B37; }}
.shoulders {{ font-family:"Shoulders",sans-serif; font-weight:900; text-transform:uppercase; line-height:.8; white-space:nowrap; }}
.disp {{ font-family:"InterDisplayP",sans-serif; line-height:1; white-space:nowrap; letter-spacing:-.025em; }}
.fit {{ display:inline-block; }}
.serif {{ font-family:"SerifIt",serif; font-style:italic; font-weight:400; line-height:1.15; color:var(--muted);
  font-variant-numeric:lining-nums proportional-nums; font-feature-settings:"lnum" 1,"onum" 0; }}
.pod h2 {{ font-size:2.4mm; line-height:1; font-weight:600; letter-spacing:.28em; text-transform:uppercase; color:var(--muted); margin-bottom:2.2mm; }}
.row {{ display:grid; grid-template-columns:6mm 1fr auto; align-items:baseline; border-top:.2mm solid var(--rule);
  padding:1.6mm 0; font-size:3.3mm; line-height:1.15; font-variant-numeric:tabular-nums lining-nums; }}
.row:last-child {{ border-bottom:.2mm solid var(--rule); }}
.row .n {{ font-weight:500; color:var(--muted); }} .row .p {{ font-weight:500; }} .row .t {{ color:var(--muted); text-align:right; }}
{css}
</style></head><body>
"""

FOOT = """
<script>
document.fonts.ready.then(() => {
  const mm = 96 / 25.4;
  document.querySelectorAll('[data-fit]').forEach(el => {
    el.style.fontSize = '100px';
    const r = el.getBoundingClientRect();
    const size = el.dataset.axis === 'h' ? r.height : r.width;
    el.style.fontSize = (100 * parseFloat(el.dataset.fit) * mm / size) + 'px';
  });
  document.querySelectorAll('[data-copy]').forEach(el => {
    el.style.fontSize = getComputedStyle(document.querySelector(el.dataset.copy)).fontSize;
  });
  window.__ready = true;
});
</script></body></html>"""

TRI = '<div class="abs tri" style="{pos}"><span class="g"></span><span class="w"></span><span class="r"></span></div>'


def frame(fx, fy, fw, fh, img, src_size, cx, cut=None, extra=""):
    """Riquadro fw x fh mm che mostra la foto a tutta altezza, centrata in orizzontale su cx (px).

    cut = (png, rows): PNG scontornato della fascia [0, rows) px, posato sopra `extra`.
    Ritorna (html, dpi_effettivi, info_ritaglio)."""
    sw, sh = src_size
    k = fh / sh                       # mm per pixel
    cw = fw / k                       # larghezza del ritaglio in px
    x0 = min(max(cx - cw / 2, 0), sw - cw)
    parts = [f'<div class="frame" style="left:{fx}mm;top:{fy}mm;width:{fw}mm;height:{fh}mm">',
             f'<img src="{img}" style="left:{-x0*k:.4f}mm;top:0;width:{sw*k:.4f}mm;height:{sh*k:.4f}mm" alt="">',
             extra]
    if cut:
        png, c0, c1, rows = cut(x0, cw)
        parts.append(f'<img src="{png}" style="left:{(c0-x0)*k:.4f}mm;top:0;width:{(c1-c0)*k:.4f}mm;height:{rows*k:.4f}mm" alt="">')
    parts.append('</div>')
    return "\n".join(parts), 25.4 / k, f"{cw:.0f}x{sh} px da x={x0:.0f}"


def cutout_maker(photo, mask, name, rows):
    def make(x0, cw):
        im = Image.open(SRC / photo)
        im = im if im.mode == "L" else im.convert("RGB")
        m = Image.open(SRC / mask).convert("L")
        c0, c1 = math.floor(x0), math.ceil(x0 + cw)
        rgba = im.crop((c0, 0, c1, rows)).copy()
        rgba.putalpha(m.crop((c0, 0, c1, rows)))
        rgba.save(SRC / name, optimize=True)
        return name, c0, c1, rows
    return make


def bw_photo():
    out = SRC / "foto_bn.jpg"
    g = ImageOps.autocontrast(Image.open(SRC / "foto.jpg").convert("L"), cutoff=0.3)
    g.save(out, quality=95, subsampling=0, dpi=(300, 300))
    return out.name


POD = """<h2>Il podio</h2>
<div class="row"><span class="n">1</span><span class="p">Max Verstappen</span><span class="t">Red Bull</span></div>
<div class="row"><span class="n">2</span><span class="p">Lewis Hamilton</span><span class="t">Ferrari</span></div>
<div class="row"><span class="n">3</span><span class="p">Kimi Antonelli</span><span class="t">Mercedes</span></div>"""
ANT = (3920, 2616)
TAM = (3500, 2334)
REPORT = []


def write(slug, pal, css, body, dpi, crop):
    html = HEAD.format(title=slug, css=css, **pal) + body + FOOT
    (SRC / f"alt_{slug}.html").write_text(html, encoding="utf-8")
    REPORT.append(f"{slug}: foto {crop}, {dpi:.0f} dpi")


# ------------------------------------------------------------------ A. Copertina
def copertina():
    pal = dict(bg="#0A110D", fg="#F3EFE6", muted="#9AA59C", rule="#2B372F")
    cut = cutout_maker("foto.jpg", "mask_antonelli.png", "cut_antonelli.png", 1000)
    mast = '<div class="abs shoulders mastA"><span class="fit" data-fit="277">Antonelli</span></div>'
    grad = '<div class="abs gradA"></div>'
    fr, dpi, crop = frame(0, 0, 297, 318, "foto.jpg", ANT, 2000, cut, mast)
    css = """
.mastA { left:10mm; top:23mm; color:#0A110D; letter-spacing:-.005em; }
.gradA { left:0; top:236mm; width:297mm; height:84mm; background:linear-gradient(to bottom, rgba(10,17,13,0) 0%, rgba(10,17,13,.55) 45%, #0A110D 100%); }
.hdrA { left:10mm; top:10mm; width:277mm; display:flex; justify-content:space-between; font-size:2.6mm; color:#0A110D; }
.hdrA b { color:#0A110D; }
.metaA { left:10mm; top:316mm; font-size:2.7mm; }
.headA { left:10mm; top:330mm; }
.headA .k { font-size:21mm; color:var(--fg); line-height:.82; }
.subA { left:10mm; top:378mm; font-size:7mm; }
.podA { right:10mm; top:330mm; width:104mm; }
.footA { left:10mm; top:402mm; width:277mm; display:flex; justify-content:space-between; font-size:2.3mm; }
"""
    body = f"""{fr}
<div class="abs gradA"></div>
<div class="abs caps hdrA"><span><b>Kimi Antonelli</b><span class="sep">·</span>Formula 1 2026</span><span>Mercedes-AMG<span class="sep">·</span><b>N° 12</b></span></div>
<div class="abs caps metaA"><b>Città del Messico</b><span class="sep">·</span>1 novembre 2026<span class="sep">·</span>Gran Premio de la Ciudad de México</div>
{TRI.format(pos="left:10mm;top:322.5mm;width:277mm")}
<div class="abs shoulders headA"><div class="k">Campione</div><div class="k">del mondo</div></div>
<div class="abs serif subA">Kimi Antonelli, a 20 anni e 68 giorni</div>
<div class="abs pod podA">{POD}</div>
"""
    write("A_copertina_scuro", pal, css, body.replace('<div class="abs gradA"></div>', grad, 1), dpi, crop)


# ------------------------------------------------------------------ B. Bianco e nero
def biancoenero():
    pal = dict(bg="#F4EFE4", fg="#14201A", muted="#6B7468", rule="#CFC8B6")
    bw = bw_photo()
    num = '<div class="abs shoulders numB"><span class="fit" data-fit="118" data-axis="w">12</span></div>'
    cut = cutout_maker(bw, "mask_antonelli.png", "cut_antonelli_bn.png", 1300)
    fr, dpi, crop = frame(16, 16, 265, 331.25, bw, ANT, 2046, cut, num)
    css = """
.numB { right:9mm; top:12mm; color:#CE2B37; letter-spacing:-.02em; line-height:.74; }
.lblB { left:16mm; top:351mm; width:265mm; display:flex; justify-content:space-between; font-size:2.5mm; }
.nameB { left:16mm; top:360mm; }
.nameB div { line-height:.8; }
.redB { color:#CE2B37; }
.colB { left:150mm; top:360mm; width:131mm; }
.colB .serif { font-size:5.4mm; margin-bottom:3.4mm; }
.colB .row { font-size:3.1mm; padding:1.35mm 0; }
"""
    body = f"""{fr}
<div class="abs caps lblB"><span><b>Città del Messico</b><span class="sep">·</span>1 novembre 2026</span><span>Gran Premio de la Ciudad de México</span></div>
<div class="abs shoulders nameB"><div><span class="fit" data-copy="#antB">Kimi</span></div><div><span id="antB" class="fit" data-fit="124">Antonelli</span></div></div>
<div class="abs colB pod">
  <div class="serif">Campione del mondo a 20 anni e 68 giorni</div>
  <div class="row"><span class="n">1</span><span class="p">Max Verstappen</span><span class="t">Red Bull</span></div>
  <div class="row"><span class="n">2</span><span class="p">Lewis Hamilton</span><span class="t">Ferrari</span></div>
  <div class="row"><span class="n redB">3</span><span class="p">Kimi Antonelli</span><span class="t">Mercedes</span></div>
</div>
"""
    write("B_bianconero_avorio", pal, css, body, dpi, crop)


# ------------------------------------------------------------------ C. Taglio verticale
def verticale():
    pal = dict(bg="#0E2C21", fg="#F3EFE6", muted="#93AB9C", rule="#2C4A3D")
    fr, dpi, crop = frame(74, 12, 211, 322, "foto.jpg", ANT, 2000)
    css = """
.vert { left:12mm; top:12mm; width:52mm; height:396mm; display:flex; align-items:center; justify-content:center; }
.vert .in { writing-mode:vertical-rl; transform:rotate(180deg); }
.vert .l { color:var(--muted); }
.metaC { left:74mm; top:339mm; font-size:2.5mm; }
.subC { left:74mm; top:352mm; font-size:7.4mm; color:var(--fg); }
.podC { left:74mm; top:372mm; width:211mm; display:grid; grid-template-columns:repeat(3,1fr); column-gap:6mm; }
.pc { border-top:.2mm solid var(--rule); padding-top:2.4mm; font-size:3.3mm; line-height:1.35; }
.pc .n { font-weight:600; margin-right:2mm; color:var(--muted); }
.pc .p { font-weight:500; }
.pc .t { display:block; color:var(--muted); padding-left:4.2mm; }
.footC { left:74mm; top:402mm; width:211mm; display:flex; justify-content:space-between; font-size:2.3mm; }
"""
    body = f"""{fr}
<div class="abs vert"><div class="in shoulders"><span class="fit" data-fit="396" data-axis="h"><span class="l">Kimi </span>Antonelli</span></div></div>
{TRI.format(pos="left:74mm;top:345mm;width:211mm")}
<div class="abs caps metaC"><b>Città del Messico</b><span class="sep">·</span>1 novembre 2026<span class="sep">·</span>Gran Premio de la Ciudad de México</div>
<div class="abs serif subC">Campione del mondo a 20 anni e 68 giorni</div>
<div class="abs podC">
  <div class="pc"><span class="n">1</span><span class="p">Max Verstappen</span><span class="t">Red Bull</span></div>
  <div class="pc"><span class="n">2</span><span class="p">Lewis Hamilton</span><span class="t">Ferrari</span></div>
  <div class="pc"><span class="n">3</span><span class="p">Kimi Antonelli</span><span class="t">Mercedes</span></div>
</div>
<div class="abs caps footC"><span>Formula 1<span class="sep">·</span>Stagione 2026</span><span><b>Mercedes-AMG</b><span class="sep">·</span>N° 12</span></div>
"""
    write("C_verticale_verde", pal, css, body, dpi, crop)


# ------------------------------------------------------------------ T. Tamberi & Barshim
def tamberi():
    pal = dict(bg="#110D0C", fg="#F3EFE6", muted="#A89F98", rule="#3A302D")
    cut = cutout_maker("foto_tamberi.jpg", "mask_tamberi.png", "cut_tamberi.png", 1150)
    mast = '<div class="abs shoulders mastT"><span class="fit" data-fit="230">Due ori</span></div>'
    fr, dpi, crop = frame(0, 0, 297, 318, "foto_tamberi.jpg", TAM, 1635, cut, mast)
    css = """
.mastT { left:10mm; top:9mm; color:#F3EFE6; letter-spacing:.005em; }
.gradT { left:0; top:236mm; width:297mm; height:84mm; background:linear-gradient(to bottom, rgba(17,13,12,0) 0%, rgba(17,13,12,.55) 45%, #110D0C 100%); }
.hdrT { left:10mm; top:10mm; width:277mm; display:flex; justify-content:flex-end; gap:0; font-size:2.6mm; color:#F3EFE6; }
.hdrT b { color:#F3EFE6; }
.metaT { left:10mm; top:316mm; font-size:2.7mm; }
.gold { left:10mm; top:322.5mm; width:277mm; height:.9mm; background:#C9A24A; }
.namesT { left:10mm; top:331mm; }
.namesT div { font-size:13.5mm; line-height:1.02; }
.quoteT { left:10mm; top:367mm; font-size:7mm; color:var(--fg); }
.quoteT small { display:block; font-size:4.2mm; color:var(--muted); margin-top:1.6mm; }
.podT { right:10mm; top:331mm; width:96mm; }
.podT .row { grid-template-columns:1fr auto auto; column-gap:4mm; }
.podT .g { color:#C9A24A; font-weight:600; }
"""
    body = f"""{fr}
<div class="abs gradT"></div>
<div class="abs caps metaT"><b>Giochi Olimpici di Tokyo 2020</b><span class="sep">·</span>1 agosto 2021<span class="sep">·</span>Finale del salto in alto maschile</div>
<div class="abs gold"></div>
<div class="abs disp namesT"><div><span style="font-weight:300">Gianmarco </span><span style="font-weight:600">Tamberi</span></div><div><span style="font-weight:300">Mutaz Essa </span><span style="font-weight:600">Barshim</span></div></div>
<div class="abs serif quoteT">«Possiamo avere due ori?»<small>Mutaz Essa Barshim al giudice di gara</small></div>
<div class="abs pod podT">
  <h2>Oro ex aequo</h2>
  <div class="row"><span class="p">Gianmarco Tamberi</span><span class="t">Italia</span><span class="g">2,37 m</span></div>
  <div class="row"><span class="p">Mutaz Essa Barshim</span><span class="t">Qatar</span><span class="g">2,37 m</span></div>
</div>
"""
    write("T_tamberi_barshim_scuro", pal, css, body, dpi, crop)


# ------------------------------------------------------------------ S. Sinner
def sinner():
    pal = dict(bg="#1F5E87", fg="#FFFFFF", muted="rgba(255,255,255,.74)", rule="rgba(255,255,255,.35)")
    fr, dpi, crop = frame(0, 0, 297, 420, "foto_sinner.jpg", (3451, 2301), 1080 + 813.5)
    css = """
.nameS { left:14mm; top:16mm; color:#fff; }
.nameS .l { font-weight:300; } .nameS .s { font-weight:600; }
.capS { left:14mm; top:48mm; font-size:2.7mm; color:rgba(255,255,255,.8); }
.capS b { color:#fff; }
.infoS { left:14mm; top:344mm; width:84mm; }
.infoS .tri { width:40mm; margin-bottom:5mm; }
.infoS .caps { font-size:2.5mm; color:rgba(255,255,255,.8); }
.infoS .caps b { color:#fff; }
.score { font-family:"InterDisplayP",sans-serif; font-weight:300; font-size:7.6mm; line-height:1; color:#fff;
         margin:3.2mm 0 2.6mm; white-space:nowrap; font-variant-numeric:tabular-nums lining-nums; letter-spacing:-.01em; }
.score span { margin-right:2.4mm; }
.infoS .serif { font-size:5.4mm; color:#fff; margin-top:5mm; }
"""
    body = f"""{fr}
<div class="abs disp nameS"><span class="fit" data-fit="172"><span class="l">Jannik </span><span class="s">Sinner</span></span></div>
<div class="abs caps capS"><b>Australian Open 2024</b><span class="sep">·</span>Melbourne</div>
<div class="abs infoS">
  <div class="tri"><span class="g"></span><span class="w"></span><span class="r"></span></div>
  <div class="caps"><b>Finale</b><span class="sep">·</span>28 gennaio 2024</div>
  <div class="score"><span>3–6</span><span>3–6</span><span>6–4</span><span>6–4</span><span>6–3</span></div>
  <div class="caps">contro <b>Daniil Medvedev</b></div>
  <div class="serif">Il primo Slam, rimontando da due set sotto.</div>
</div>
"""
    write("S_sinner_blu", pal, css, body, dpi, crop)


# ------------------------------------------------------------------ K. Kimi, stile Tamberi
KCSS = """
.gradK { left:0; top:236mm; width:297mm; height:84mm; background:linear-gradient(to bottom, rgba(10,17,13,0) 0%, rgba(10,17,13,.55) 45%, #0A110D 100%); }
.metaK { left:10mm; top:316mm; font-size:2.7mm; }
.namesK { left:10mm; top:331mm; }
.namesK div { font-size:13.5mm; line-height:1.02; }
.namesK .m { color:var(--muted); }
.lineK { left:10mm; top:367mm; width:175mm; font-size:6.4mm; color:var(--fg); }
.podK { right:10mm; top:331mm; width:96mm; }
"""
KBODY = f"""
<div class="abs gradK"></div>
<div class="abs caps metaK"><b>Città del Messico</b><span class="sep">·</span>1 novembre 2026<span class="sep">·</span>Gran Premio de la Ciudad de México</div>
{TRI.format(pos="left:10mm;top:322.5mm;width:277mm")}
<div class="abs disp namesK"><div><span style="font-weight:300">Kimi </span><span style="font-weight:600">Antonelli</span></div><div class="m"><span style="font-weight:300">Campione del mondo 2026</span></div></div>
<div class="abs serif lineK">{{line}}</div>
<div class="abs pod podK">{POD}</div>
"""
KPAL = dict(bg="#0A110D", fg="#F3EFE6", muted="#9AA59C", rule="#2B372F")


def kimi_numeri():
    cut = cutout_maker("foto.jpg", "mask_antonelli.png", "cut_antonelli.png", 1000)
    nums = """<div class="abs shoulders numK numL">20<div class="serif lab">anni</div></div>
<div class="abs shoulders numK numR">68<div class="serif lab">giorni</div></div>"""
    fr, dpi, crop = frame(0, 0, 297, 318, "foto.jpg", ANT, 2000, cut, nums)
    css = KCSS + """
.numK { top:8mm; font-size:96mm; line-height:.8; color:#0A110D; letter-spacing:-.01em; }
.numL { left:9mm; } .numR { right:9mm; text-align:right; }
.numK .lab { font-size:9mm; color:#0A110D; text-transform:none; margin-top:3mm; }
"""
    body = fr + KBODY.replace("{line}", "Il più giovane campione del mondo di sempre in Formula 1")
    write("K1_kimi_20_68", KPAL, css, body, dpi, crop)


def kimi_copertina():
    cut = cutout_maker("foto.jpg", "mask_antonelli.png", "cut_antonelli.png", 1000)
    mast = '<div class="abs shoulders mastK"><span class="fit" data-fit="277">Antonelli</span></div>'
    fr, dpi, crop = frame(0, 0, 297, 318, "foto.jpg", ANT, 2000, cut, mast)
    css = KCSS + """
.mastK { left:10mm; top:12mm; color:#0A110D; letter-spacing:-.005em; }
"""
    body = fr + KBODY.replace("{line}", "Campione del mondo a 20 anni e 68 giorni")
    write("K2_kimi_copertina", KPAL, css, body, dpi, crop)


if __name__ == "__main__":
    copertina(); biancoenero(); verticale(); tamberi(); sinner(); kimi_numeri(); kimi_copertina()
    print("\n".join(REPORT))
