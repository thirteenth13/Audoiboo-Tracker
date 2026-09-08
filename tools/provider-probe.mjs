import { chromium } from 'playwright';
import fs from 'node:fs/promises';

const author = process.env.PROBE_AUTHOR || 'Роман Прокофьев';
const series = process.env.PROBE_SERIES || 'Сфера Миров';
const headless = process.env.HEADLESS !== 'false';

const cyrMap = {
  а:'a',б:'b',в:'v',г:'g',д:'d',е:'e',ё:'e',ж:'zh',з:'z',и:'i',й:'y',к:'k',л:'l',м:'m',н:'n',о:'o',п:'p',р:'r',с:'s',т:'t',у:'u',ф:'f',х:'h',ц:'c',ч:'ch',ш:'sh',щ:'shh',ъ:'',ы:'y',ь:'',э:'e',ю:'yu',я:'ya'
};
const slugify = value => value.toLowerCase().split('').map(ch => cyrMap[ch] ?? (/[a-z0-9]/.test(ch) ? ch : '-')).join('').replace(/-+/g,'-').replace(/^-|-$/g,'');
const tokens = value => value.toLowerCase().replace(/ё/g,'е').match(/[\p{L}\p{N}]+/gu) || [];
const sameAuthor = (value, expected) => {
  const have = new Set(tokens(value));
  return tokens(expected).every(t => have.has(t));
};
const surname = tokens(author).at(-1) || '';
const surnameInitial = surname[0]?.toUpperCase() || '';
const givenInitial = tokens(author)[0]?.[0]?.toUpperCase() || '';

const browser = await chromium.launch({ headless });
const context = await browser.newContext({
  locale: 'ru-RU',
  userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/127 Safari/537.36 AudoibooProviderProbe/1.0'
});
const page = await context.newPage();
page.setDefaultTimeout(12000);
page.setDefaultNavigationTimeout(20000);

const report = { author, series, startedAt: new Date().toISOString(), providers: {} };

async function inspect(url, linkSelector, note) {
  const started = Date.now();
  try {
    const response = await page.goto(url, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(350);
    const links = await page.locator(linkSelector).evaluateAll(nodes => nodes.slice(0, 300).map(a => ({
      text: (a.textContent || '').trim(),
      href: a.href || a.getAttribute('href') || ''
    })));
    return { ok: true, requestedUrl: url, finalUrl: page.url(), status: response?.status() ?? null, title: await page.title(), links, elapsedMs: Date.now() - started, note };
  } catch (error) {
    return { ok: false, requestedUrl: url, finalUrl: page.url(), error: String(error), elapsedMs: Date.now() - started, note, links: [] };
  }
}

async function probeBaza() {
  const out = { steps: [], authorUrl: null, matchingBooks: [] };
  for (const initial of [surnameInitial, givenInitial].filter(Boolean)) {
    for (let p = 1; p <= 5 && !out.authorUrl; p++) {
      const url = `https://baza-knig.info/authors/let-${encodeURIComponent(initial)}${p > 1 ? `?page=${p}` : ''}`;
      const step = await inspect(url, "a[href*='/avtor-']", `author directory ${initial} page ${p}`);
      out.steps.push({ ...step, links: step.links.slice(0, 20) });
      const hit = step.links.find(x => sameAuthor(x.text, author) || (surname && x.href.toLowerCase().includes(slugify(surname))));
      if (hit) out.authorUrl = hit.href;
      if (!step.ok) break;
    }
  }
  if (out.authorUrl) {
    const step = await inspect(out.authorUrl, "a[href*='/audio-']", 'author page');
    out.steps.push({ ...step, links: step.links.slice(0, 40) });
    out.matchingBooks = step.links.filter(x => /игра кота|астральн|сфера миров/i.test(x.text)).slice(0, 30);
  }
  return out;
}

async function probeIzib() {
  const out = { steps: [], authorUrl: null, seriesLinks: [], matchingBooks: [] };
  for (const initial of [surnameInitial, givenInitial].filter(Boolean)) {
    for (let p = 1; p <= 8 && !out.authorUrl; p++) {
      const url = `https://izib.uk/authors?l=${encodeURIComponent(initial)}${p > 1 ? `&p=${p}` : ''}`;
      const step = await inspect(url, "a[href*='/author']", `author directory ${initial} page ${p}`);
      out.steps.push({ ...step, links: step.links.slice(0, 20) });
      const hit = step.links.find(x => sameAuthor(x.text, author) || (surname && x.href.toLowerCase().includes(slugify(surname))));
      if (hit) out.authorUrl = hit.href;
      if (!step.ok) break;
    }
  }
  if (out.authorUrl) {
    const step = await inspect(out.authorUrl, "a[href*='/serie'], a[href*='/art']", 'author page');
    out.steps.push({ ...step, links: step.links.slice(0, 60) });
    out.seriesLinks = step.links.filter(x => x.href.includes('/serie'));
    out.matchingBooks = step.links.filter(x => x.href.includes('/art') && /игра кота|астральн|сфера миров/i.test(x.text)).slice(0, 30);
  }
  return out;
}

async function probeLis() {
  const out = { steps: [], directSeriesUrl: null, authorUrl: null, matchingBooks: [] };
  const directUrl = `https://lis10book.com/serie/${slugify(series)}/`;
  const direct = await inspect(directUrl, "a[href*='/audio/']", 'direct series slug');
  out.steps.push({ ...direct, links: direct.links.slice(0, 40) });
  if (direct.ok && direct.status && direct.status < 400 && direct.links.length) {
    out.directSeriesUrl = direct.finalUrl;
    out.matchingBooks = direct.links.slice(0, 30);
    return out;
  }
  const variants = [slugify(author), slugify(tokens(author).reverse().join(' '))];
  for (const slug of [...new Set(variants)]) {
    const url = `https://lis10book.com/avtor/${slug}/`;
    const step = await inspect(url, "a[href*='/audio/']", `author slug ${slug}`);
    out.steps.push({ ...step, links: step.links.slice(0, 40) });
    if (step.ok && step.status && step.status < 400 && step.links.length) {
      out.authorUrl = step.finalUrl;
      out.matchingBooks = step.links.filter(x => /игра кота|астральн|сфера миров/i.test(x.text)).slice(0, 30);
      break;
    }
  }
  return out;
}

for (const [name, fn] of Object.entries({ 'baza-knig': probeBaza, izib: probeIzib, lis10book: probeLis })) {
  try { report.providers[name] = await fn(); }
  catch (error) { report.providers[name] = { fatal: String(error) }; }
}

report.finishedAt = new Date().toISOString();
await browser.close();
await fs.mkdir('provider-probe-output', { recursive: true });
await fs.writeFile('provider-probe-output/report.json', JSON.stringify(report, null, 2));

const md = ['# Audoiboo provider probe', '', `- Author: **${author}**`, `- Series: **${series}**`, ''];
for (const [name, data] of Object.entries(report.providers)) {
  md.push(`## ${name}`);
  md.push(`- Author URL: ${data.authorUrl || 'not found'}`);
  if (data.directSeriesUrl) md.push(`- Direct series URL: ${data.directSeriesUrl}`);
  md.push(`- Matching books: ${(data.matchingBooks || []).length}`);
  for (const item of (data.matchingBooks || []).slice(0, 15)) md.push(`  - ${item.text} — ${item.href}`);
  md.push('');
  for (const step of data.steps || []) md.push(`- ${step.note}: status=${step.status ?? 'n/a'} final=${step.finalUrl || step.requestedUrl} links=${step.links?.length ?? 0} time=${step.elapsedMs}ms${step.error ? ` error=${step.error}` : ''}`);
  md.push('');
}
await fs.writeFile('provider-probe-output/report.md', md.join('\n'));
console.log(md.join('\n'));
