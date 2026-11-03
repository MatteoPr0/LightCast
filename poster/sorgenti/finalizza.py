#!/usr/bin/env python3
"""Sistema la pagina a 297x420 mm esatti, sposta i PDF di stampa in ../stampa
ed esporta i JPG a 300 dpi (sRGB) per i laboratori fotografici.

Richiede: pip install pillow pypdf pypdfium2
"""
from pathlib import Path
from PIL import ImageCms
from pypdf import PdfReader, PdfWriter
from pypdf.generic import RectangleObject
import pypdfium2 as pdfium

HERE = Path(__file__).resolve().parent
ALT = HERE.parent / "alternative"
OUT = HERE.parent / "stampa"
OUT.mkdir(exist_ok=True)
W, H = 297 / 25.4 * 72, 420 / 25.4 * 72
ICC = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()

for name in ["Antonelli", "Tamberi_Barshim", "Sinner"]:
    src = ALT / f"Poster_A3_STAMPA_{name}.pdf"
    pdf = OUT / f"{name}_A3_EN_passepartout.pdf"
    w = PdfWriter(clone_from=PdfReader(src))
    p = w.pages[0]
    top = float(p.mediabox.top)
    box = RectangleObject([0, top - H, W, top])
    p.mediabox = p.cropbox = p.trimbox = p.bleedbox = box
    w.write(pdf)
    img = pdfium.PdfDocument(str(pdf))[0].render(scale=300 / 72).to_pil().convert("RGB")
    jpg = OUT / f"{name}_A3_EN_passepartout_300dpi.jpg"
    img.save(jpg, quality=95, subsampling=0, dpi=(300, 300), icc_profile=ICC)
    print(pdf.name, "+", jpg.name, img.size)
