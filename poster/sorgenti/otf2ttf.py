import sys
from fontTools.ttLib import TTFont, newTable
from fontTools.pens.cu2quPen import Cu2QuPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
src, dst = sys.argv[1:3]
f = TTFont(src)
gs = f.getGlyphSet(); order = f.getGlyphOrder()
glyf = newTable("glyf"); glyf.glyphOrder = order; glyf.glyphs = {}
for n in order:
    pen = TTGlyphPen(gs); gs[n].draw(Cu2QuPen(pen, 1.0, reverse_direction=True)); glyf.glyphs[n] = pen.glyph()
f["glyf"] = glyf; f["loca"] = newTable("loca")
maxp = f["maxp"]; maxp.tableVersion = 0x00010000
for a in ("maxZones","maxTwilightPoints","maxStorage","maxFunctionDefs","maxInstructionDefs","maxStackElements","maxSizeOfInstructions","maxComponentElements"):
    setattr(maxp, a, 0)
maxp.maxZones = 1
post = f["post"]; post.formatType = 2.0; post.extraNames = []; post.mapping = {}; post.glyphOrder = order
del f["CFF "]
if "VORG" in f: del f["VORG"]
f.sfntVersion = "\x00\x01\x00\x00"
f.save(dst); print("ok", dst)
