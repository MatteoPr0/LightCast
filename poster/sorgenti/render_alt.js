// Stampa i concept verticali in PDF A3 (297x420 mm) con Chromium.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const src = __dirname;
const out = path.resolve(src, '..', 'alternative');
fs.mkdirSync(out, { recursive: true });

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  for (const f of fs.readdirSync(src).filter(f => /^alt_.*\.html$/.test(f)).sort()) {
    await page.goto('file://' + path.join(src, f));
    await page.waitForFunction(() => window.__ready === true &&
      [...document.images].every(i => i.complete && i.naturalWidth > 0));
    const pdf = path.join(out, 'Poster_A3_' + f.slice(4, -5) + '.pdf');
    await page.pdf({ path: pdf, width: '297mm', height: '420mm', printBackground: true,
                     preferCSSPageSize: true, margin: { top: 0, right: 0, bottom: 0, left: 0 } });
    console.log('PDF', pdf);
  }
  await browser.close();
})();
