// Renders the README images from screens.html with Playwright (Chromium).
//   PW=$(npm root -g)/playwright node render.js
// Behind a proxy, set launch({ proxy }) so the Roboto web font loads.
const { chromium } = require(process.env.PW);
(async () => {
  const browser = await chromium.launch({ proxy: { server: 'http://127.0.0.1:40367' } });
  const page = await browser.newPage({ viewport: { width: 360, height: 780 }, deviceScaleFactor: 2 });
  for (const s of ['popup', 'languages', 'home']) {
    await page.goto('file://' + __dirname + '/screens.html?scene=' + s);
    await page.evaluate(() => document.fonts.ready);
    const font = await page.evaluate(() => document.fonts.check('16px Roboto'));
    await page.screenshot({ path: __dirname + '/' + { popup: 'instant-popup.png', languages: 'language-picker.png', home: 'home-screen.png' }[s] });
    console.log(s, 'roboto:', font);
  }
  await browser.close();
})();
