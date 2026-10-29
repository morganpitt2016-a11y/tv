// Renders the generated SVG layers to 1920x1080 PNGs with Playwright.
// Usage: node render.cjs <svgDir> <outDir>
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const [svgDir, outDir] = process.argv.slice(2);
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  for (const name of ['wallpaper-back', 'wallpaper-front']) {
    await p.goto('file://' + path.resolve(svgDir, name + '.svg'));
    await p.waitForTimeout(300);
    await p.screenshot({ path: path.resolve(outDir, name + '.png'), omitBackground: true });
  }
  await b.close();
})();
