// Generates the local wallpaper + thumbnail SVGs in public/.
// Run with: node scripts/gen-art.mjs
//
// The wallpaper is split in two layers so the clock can sit *between*
// them, the way the mountain peak overlaps the clock in the reference:
//   wallpaper-back.svg  - sky and clouds
//   wallpaper-front.svg - mountains, forest, lake, rocks (transparent sky)
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = process.argv[2] || join(dirname(fileURLToPath(import.meta.url)), 'out');
mkdirSync(root, { recursive: true });

const W = 1920;
const H = 1080;
const WATER = 578; // waterline y

// Deterministic PRNG so the art is identical on every run.
function rng(seed) {
  return () => {
    seed |= 0;
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const r = rng(47);
const rand = (a, b) => a + r() * (b - a);
const f = (n) => n.toFixed(1);

// A spruce silhouette: a narrow jagged triangle with tiered branches.
function spruce(x, baseY, h) {
  const w = h * rand(0.26, 0.34);
  const tiers = Math.max(4, Math.round(h / 9));
  const left = [];
  const right = [];
  for (let i = 0; i <= tiers; i++) {
    const t = i / tiers;
    const y = baseY - h + t * h;
    const half = (w / 2) * t;
    const jag = i % 2 === 0 ? 1 : 0.55;
    left.push(`${f(x - half * jag)},${f(y)}`);
    right.unshift(`${f(x + half * jag)},${f(y)}`);
  }
  return `M${f(x)},${f(baseY - h - 4)} L${left.join(' L')} L${right.join(' L')} Z`;
}

// Tree line along a ridge function.
function forest(x0, x1, ridge, minH, maxH, step) {
  const parts = [];
  for (let x = x0; x <= x1; x += rand(step * 0.6, step * 1.3)) {
    const base = ridge(x) + rand(6, 26);
    parts.push(spruce(x, base, rand(minH, maxH)));
  }
  return parts.join(' ');
}

function hillPolygon(x0, x1, ridge, bottom) {
  const pts = [];
  for (let x = x0; x <= x1; x += 12) pts.push(`${f(x)},${f(ridge(x) + 18)}`);
  return `M${x0},${bottom} L${pts.join(' L')} L${x1},${bottom} Z`;
}

// Ridges: left hillside falls from top-left to the shore, right rises to top-right.
const leftRidge = (x) => 150 + Math.pow(Math.max(x, 0) / 860, 1.25) * 400 + Math.sin(x / 37) * 9;
const rightRidge = (x) => {
  const t = (x - 1120) / 800;
  return 545 - Math.pow(Math.max(t, 0), 0.9) * 390 + Math.sin(x / 41) * 10;
};

// ---------- Back layer: sky + clouds ----------
const clouds = [];
for (let i = 0; i < 26; i++) {
  const cx = rand(-100, 2020);
  const cy = rand(170, 430);
  const rx = rand(120, 380);
  const ry = rand(10, 26);
  const lit = cy > 250 && cx > 900 && cx < 1800;
  clouds.push(
    `<ellipse cx="${f(cx)}" cy="${f(cy)}" rx="${f(rx)}" ry="${f(ry)}" fill="${lit ? '#c99aa6' : cy > 250 ? '#6a5468' : '#3a3442'}" opacity="${f(rand(0.35, 0.7))}"/>`
  );
}

const back = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}">
  <defs>
    <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#2e2b37"/>
      <stop offset="0.12" stop-color="#4a4253"/>
      <stop offset="0.24" stop-color="#7a6680"/>
      <stop offset="0.32" stop-color="#b38ea2"/>
      <stop offset="0.39" stop-color="#dfafb6"/>
      <stop offset="0.46" stop-color="#f4c9bd"/>
    </linearGradient>
    <radialGradient id="glow" cx="0.52" cy="0.4" r="0.38">
      <stop offset="0" stop-color="#ffd2c2" stop-opacity="0.85"/>
      <stop offset="0.5" stop-color="#d79bb0" stop-opacity="0.2"/>
      <stop offset="1" stop-color="#d79bb0" stop-opacity="0"/>
    </radialGradient>
    <filter id="soft" x="-20%" y="-50%" width="140%" height="200%"><feGaussianBlur stdDeviation="14"/></filter>
    <filter id="grain"><feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" seed="3"/><feColorMatrix values="0 0 0 0 0.5  0 0 0 0 0.5  0 0 0 0 0.5  0 0 0 0.06 0"/></filter>
  </defs>
  <rect width="${W}" height="${H}" fill="url(#sky)"/>
  <rect width="${W}" height="${H}" fill="url(#glow)"/>
  <g filter="url(#soft)">${clouds.join('')}</g>
  <rect width="${W}" height="${H}" filter="url(#grain)"/>
</svg>`;

// ---------- Front layer: terrain, lake, rocks ----------
const peak = `
  <path d="M700,560 L790,470 L840,455 L880,405 L915,372 L945,348 L968,372 L990,392 L1012,372 L1030,388 L1060,425 L1100,445 L1170,470 L1260,540 L1260,580 L700,580 Z" fill="#2a3040"/>
  <path d="M945,348 L968,372 L955,410 L930,470 L905,520 L880,520 L915,440 Z" fill="#46506a" opacity="0.75"/>
  <path d="M1012,372 L1030,388 L1050,430 L1020,470 L1005,430 Z" fill="#46506a" opacity="0.6"/>
  <path d="M840,455 L880,405 L870,470 L820,520 Z" fill="#3a4258" opacity="0.7"/>
  <path d="M640,560 L720,500 L780,520 L830,505 L900,540 L1000,520 L1100,530 L1180,505 L1260,520 L1320,560 Z" fill="#1d2330"/>`;

const shoreTrees = forest(700, 1230, () => 560, 14, 34, 7);
const rows = (x0, x1, ridge, n) =>
  Array.from({ length: n }, (_, i) =>
    forest(x0, x1, (x) => ridge(x) + i * 55, 40 + i * 6, 130 - i * 10, 9 + i * 2)
  );
const leftTrees = rows(-20, 900, leftRidge, 4);
const rightTrees = rows(1110, 1940, rightRidge, 4);
const leftHill = hillPolygon(-20, 900, leftRidge, 640);
const rightHill = hillPolygon(1110, 1940, rightRidge, 640);

const terrain = `
  ${peak}
  <path d="${leftHill}" fill="#0c120f"/>
  <path d="${rightHill}" fill="#0c120f"/>
  ${leftTrees.map((d, i) => `<path d="${d}" fill="${['#080d0a', '#0f1612', '#131b17', '#162019'][i]}"/>`).join('')}
  ${rightTrees.map((d, i) => `<path d="${d}" fill="${['#080d0a', '#0f1612', '#131b17', '#162019'][i]}"/>`).join('')}
  <path d="${shoreTrees}" fill="#111915"/>`;

// Foreground rocks along the lower shores.
function rocks(x0, x1, yTop, yBottom, seed) {
  const rr = rng(seed);
  const parts = [];
  for (let i = 0; i < 40; i++) {
    const cx = x0 + rr() * (x1 - x0);
    const cy = yTop + rr() * (yBottom - yTop);
    const w = 30 + rr() * 110;
    const h = 12 + rr() * 34;
    const tone = Math.round(26 + rr() * 26);
    const c = `rgb(${tone},${tone + 2},${tone + 6})`;
    const lit = `rgb(${tone + 30},${tone + 30},${tone + 36})`;
    parts.push(
      `<path d="M${f(cx - w / 2)},${f(cy + h / 2)} Q${f(cx - w * 0.45)},${f(cy - h * 0.3)} ${f(cx - w * 0.05)},${f(cy - h / 2)} Q${f(cx + w * 0.4)},${f(cy - h * 0.4)} ${f(cx + w / 2)},${f(cy + h / 2)} Z" fill="${c}"/>` +
      `<path d="M${f(cx - w * 0.3)},${f(cy - h * 0.15)} Q${f(cx - w * 0.05)},${f(cy - h * 0.5)} ${f(cx + w * 0.3)},${f(cy - h * 0.25)} Q${f(cx)},${f(cy - h * 0.1)} ${f(cx - w * 0.3)},${f(cy - h * 0.15)} Z" fill="${lit}" opacity="0.3"/>`
    );
  }
  return parts.join('');
}

const front = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}">
  <defs>
    <linearGradient id="lake" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#d3a4b0"/>
      <stop offset="0.4" stop-color="#9a7488"/>
      <stop offset="1" stop-color="#2c2a36"/>
    </linearGradient>
    <linearGradient id="lakeEdge" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#0d1210" stop-opacity="0.95"/>
      <stop offset="0.3" stop-color="#0d1210" stop-opacity="0"/>
      <stop offset="0.7" stop-color="#0d1210" stop-opacity="0"/>
      <stop offset="1" stop-color="#0d1210" stop-opacity="0.95"/>
    </linearGradient>
    <clipPath id="water"><rect x="0" y="${WATER}" width="${W}" height="${H - WATER}"/></clipPath>
    <filter id="ripple" x="0" y="0" width="100%" height="100%">
      <feTurbulence type="fractalNoise" baseFrequency="0.002 0.09" numOctaves="2" seed="7" result="n"/>
      <feDisplacementMap in="SourceGraphic" in2="n" scale="14" xChannelSelector="R" yChannelSelector="G"/>
      <feGaussianBlur stdDeviation="0.8"/>
    </filter>
    <filter id="grain2"><feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" seed="5"/><feColorMatrix values="0 0 0 0 0.5  0 0 0 0 0.5  0 0 0 0 0.5  0 0 0 0.07 0"/></filter>
    <mask id="noSky"><rect width="${W}" height="${H}" fill="#000"/><rect x="0" y="${WATER}" width="${W}" height="${H - WATER}" fill="#fff"/></mask>
  </defs>
  <g id="land">${terrain}</g>
  <g clip-path="url(#water)">
    <rect x="0" y="${WATER}" width="${W}" height="${H - WATER}" fill="url(#lake)"/>
    <g filter="url(#ripple)" opacity="0.85" transform="translate(0 ${WATER * 2}) scale(1 -1)">${terrain}</g>
    <rect x="0" y="${WATER}" width="${W}" height="${H - WATER}" fill="url(#lakeEdge)"/>
  </g>
  <path d="M0,700 L180,640 L420,660 L560,690 L620,760 L560,840 L300,880 L0,900 Z" fill="#141a17" opacity="0.92"/>
  <path d="M1920,560 L1700,600 L1520,660 L1400,720 L1460,800 L1700,830 L1920,860 Z" fill="#141a17" opacity="0.92"/>
  ${rocks(1480, 1920, 660, 840, 11)}
  ${rocks(0, 520, 720, 880, 23)}
  <path d="M0,880 L300,860 L620,900 L1000,940 L1400,920 L1920,880 L1920,1080 L0,1080 Z" fill="#1a1d20"/>
  ${rocks(0, 1920, 900, 1060, 31)}
  <rect width="${W}" height="${H}" filter="url(#grain2)" mask="url(#noSky)"/>
</svg>`;

writeFileSync(join(root, 'wallpaper-back.svg'), back);
writeFileSync(join(root, 'wallpaper-front.svg'), front);

console.log('wrote wallpaper-back.svg and wallpaper-front.svg');
