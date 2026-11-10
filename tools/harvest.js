// Runs in a browser tab on https://archive.org (same-origin fetches). Fills window.__H with stream-ready video
// files for each bucket in window.__BUCKETS ([tag, kind, [queries]]). Poll window.__H.done / progress.
(() => {
  const BUCKETS = window.__BUCKETS;
  const KIND = {
    E: [8 * 60, 75 * 60, 160], M: [60 * 60, 240 * 60, 45], A: [15 * 60, 420 * 60, 40],
    V: [90, 12 * 60, 250], C: [8, 180, 400], X: [5 * 60, 240 * 60, 90],
  };
  const PER_ITEM = { E: 80, M: 2, A: 12, V: 60, C: 120, X: 30 };
  const JUNK = /\b(trailer|teaser|review|reaction|reacts?|podcast|commentary|recap|parody|fan ?film|fan ?edit|soundtrack|ost|audiobook|audio drama|interview|gameplay|let'?s play|walkthrough|unboxing|theme song|vlog|explained|top \d+|ranking|tribute|amv|lyrics?|karaoke|cover|remix|sped up|nightcore|tiktok|shorts|asmr|minecraft|roblox|fortnite|gacha|ytp|youtube poop|blooper reel|deleted scenes?|behind the scenes|featurette|sneak peek|preview|clip|scene|music video maker|lesson|tutorial)\b/i;
  const BAD_COLL = new Set(['tvarchive', 'tvnews', 'TV-ALJAZ', 'opensource_audio']);
  const H = window.__H = { done: false, progress: 0, bucketsDone: 0, records: [], seen: new Set(), errors: 0, log: [] };
  const sleep = ms => new Promise(r => setTimeout(r, ms));

  async function getJson(url, tries = 3) {
    for (let i = 0; i < tries; i++) {
      try {
        const r = await fetch(url, { credentials: 'omit' });
        if (r.status === 429 || r.status >= 500) { await sleep(2000 * (i + 1)); continue; }
        if (!r.ok) return null;
        return await r.json();
      } catch (e) { await sleep(1000 * (i + 1)); }
    }
    H.errors++;
    return null;
  }

  const norm = s => (s || '').toLowerCase().replace(/&/g, ' and ').replace(/[^a-z0-9]+/g, ' ').trim();
  const words = q => norm(q).split(' ').filter(w => w && !['the', 'of', 'a', 'and', 'with'].includes(w));
  function relevant(title, q) {
    const tw = norm(title).split(' ');
    return words(q).every(w => tw.some(t => t === w || (w.length >= 3 && t.startsWith(w))));
  }
  function seconds(len) {
    if (len == null) return 0;
    const s = String(len);
    if (s.includes(':')) return s.split(':').reduce((a, p) => a * 60 + parseFloat(p || 0), 0);
    return parseFloat(s) || 0;
  }
  const FMT_RANK = f => {
    f = (f || '').toLowerCase();
    if (f === 'h.264') return 0;
    if (f === 'h.264 ia') return 1;
    if (f === 'mpeg4') return 2;
    if (f === 'h.264 hd') return 3;
    if (f === '512kb mpeg4') return 4;
    return 9;
  };
  const baseOf = n => n.replace(/\.(ia\.)?(mp4|m4v|mkv|avi|mpg|mpeg|ogv|wmv|mov|webm|flv|vob|ts)$/i, '').replace(/_512kb$/i, '').replace(/\.ia$/i, '');
  const cleanName = n => baseOf(n.split('/').pop()).replace(/[._]+/g, ' ').replace(/\s+/g, ' ').trim();

  async function harvestItem(bi, kind, doc) {
    const md = await getJson(`/metadata/${encodeURIComponent(doc.identifier)}`);
    if (!md || md.is_dark || !md.files || !md.metadata) return [];
    if (String(md.metadata['access-restricted-item']) === 'true') return [];
    const colls = [].concat(md.metadata.collection || []);
    if (colls.some(c => BAD_COLL.has(c))) return [];
    const [min, max] = KIND[kind];
    const groups = new Map();
    for (const f of md.files) {
      if (f.private === 'true' || f.private === true) continue;
      const name = f.name || '';
      const base = baseOf(name);
      const g = groups.get(base) || { len: 0, mp4: [] };
      g.len = Math.max(g.len, seconds(f.length));
      if (/\.(mp4|m4v)$/i.test(name) && FMT_RANK(f.format) < 9) g.mp4.push(f);
      groups.set(base, g);
    }
    const yearRaw = String(md.metadata.year || md.metadata.date || '').match(/(19|20)\d\d/);
    const year = yearRaw ? parseInt(yearRaw[0]) : null;
    const itemTitle = [].concat(md.metadata.title || doc.title || doc.identifier)[0];
    const vids = [...groups.entries()].filter(([, g]) => g.mp4.length && g.len >= min && g.len <= max);
    const out = [];
    for (const [base, g] of vids.sort((a, b) => a[0].localeCompare(b[0], undefined, { numeric: true })).slice(0, PER_ITEM[kind])) {
      const f = g.mp4.sort((a, b) => FMT_RANK(a.format) - FMT_RANK(b.format))[0];
      const title = vids.length > 1 ? cleanName(f.name) : itemTitle;
      if (kind !== 'C' && kind !== 'V' && JUNK.test(title)) continue;
      out.push([bi, doc.identifier, f.name, String(title).slice(0, 200), Math.round(g.len), year, String(itemTitle).slice(0, 120), FMT_RANK(f.format)]);
    }
    return out;
  }

  async function harvestBucket(bi) {
    const [tag, kind, queries] = BUCKETS[bi];
    const target = KIND[kind][2];
    let got = 0;
    // Round-robin over the bucket's searches so a channel gets several different shows, not 160 of one.
    const perQuery = Math.max(1, Math.ceil(target / queries.length));
    for (const q of queries) {
      if (got >= target) break;
      const w = words(q).map(x => x.replace(/[^a-z0-9]/g, '')).filter(Boolean);
      if (!w.length) continue;
      const titleQ = w.map(x => (x.length >= 3 && /^\d+$/.test(x) && x.length < 4) ? x + '*' : x).join(' AND ');
      const url = `/advancedsearch.php?q=${encodeURIComponent(`title:(${titleQ}) AND mediatype:(movies) AND -collection:(tvarchive) AND -collection:(tvnews)`)}` +
        `&fl[]=identifier&fl[]=title&fl[]=downloads&rows=30&sort[]=downloads+desc&output=json`;
      const res = await getJson(url);
      const docs = ((res && res.response && res.response.docs) || [])
        .filter(d => !H.seen.has(d.identifier) && relevant([].concat(d.title || '')[0], q) && (kind === 'C' || kind === 'V' || !JUNK.test([].concat(d.title || '')[0])));
      let gotQ = 0;
      for (let i = 0; i < docs.length && gotQ < perQuery && got < target; i += 3) {
        const batch = docs.slice(i, i + 3);
        batch.forEach(d => H.seen.add(d.identifier));
        const recs = (await Promise.all(batch.map(d => harvestItem(bi, kind, d)))).flat();
        for (const r of recs) { if (gotQ >= perQuery * 2 || got >= target) break; H.records.push(r); got++; gotQ++; }
        if (i >= 9) break; // at most 12 items per search
      }
    }
    H.log.push(`${tag}:${got}`);
  }

  (async () => {
    let next = 0;
    const worker = async () => {
      while (next < BUCKETS.length) {
        const bi = next++;
        try { await harvestBucket(bi); } catch (e) { H.errors++; H.log.push(`${BUCKETS[bi][0]}:ERR ${e}`); }
        H.bucketsDone++;
        H.progress = H.bucketsDone / BUCKETS.length;
      }
    };
    await Promise.all([worker(), worker(), worker(), worker()]);
    H.done = true;
  })();
  return 'started';
})();
