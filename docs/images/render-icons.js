// Renders the legacy (pre-Android 8) PNG launcher icons from the adaptive icon's paths.
//   PW=$(npm root -g)/playwright node docs/images/render-icons.js
const { chromium } = require(process.env.PW);
const path = require('path');
const res = path.join(__dirname, '../../app/src/main/res');
const sizes = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
// Same paths as res/drawable/ic_launcher_foreground.xml. The adaptive icon's visible area is
// the middle 72 of 108 units, so the viewBox crops to it; the background becomes a circle.
const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="100%" height="100%">
  <circle cx="54" cy="54" r="34" fill="#1E293B"/>
  <path fill="#60A5FA" d="M34,32h40a6,6 0,0 1,6 6v26a6,6 0,0 1,-6 6H52l-10,9v-9h-8a6,6 0,0 1,-6 -6V38a6,6 0,0 1,6 -6z"/>
  <path fill="#0F172A" d="M37,40h6.5l3.5,14l4.2,-14h5.6l4.2,14l3.5,-14H71l-7,22h-6.2L54,48.5L50.2,62H44z"/>
</svg>`;
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  for (const [dpi, px] of Object.entries(sizes)) {
    await page.setViewportSize({ width: px, height: px });
    await page.setContent(`<html><body style="margin:0;background:transparent">${svg}</body></html>`);
    const out = path.join(res, `mipmap-${dpi}`, 'ic_launcher.png');
    require('fs').mkdirSync(path.dirname(out), { recursive: true });
    await page.screenshot({ path: out, omitBackground: true });
    console.log(out, px);
  }
  await browser.close();
})();
