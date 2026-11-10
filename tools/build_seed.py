#!/usr/bin/env python3
"""
Turns a harvest (tools/harvest.js output: [bucket, identifier, fileName, title, seconds, year, itemTitle, fmtRank])
into catalog assets and writes them into the bundled lineup manifest next to the channels.

    python3 tools/build_seed.py harvest.json
"""
import hashlib, json, os, re, sys, urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from seed_buckets import B, B2  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Extra pools each bucket's recordings also join, so related channels and blocks share them.
ALIASES = {
    "nicktoons": ["nicktoons-classic"], "nicktoons-classic": ["nicktoons"],
    "toonami": ["anime-classics"], "anime-classics": ["toonami"], "toonami-adult-swim": ["toonami", "adult-swim"],
    "star-trek-tng": ["star-trek"], "star-trek-ds9": ["star-trek"], "star-trek-voyager": ["star-trek"],
    "star-trek-movie": ["star-trek-movie"], "simpsons": ["animated-sitcom"], "x-files-paranormal": ["sci-fi"],
    "fox-kids": ["cartoon"], "kids-wb": ["cartoon"], "syndicated-cartoon": ["disney-afternoon", "cartoon"],
    "disney-afternoon": ["syndicated-cartoon"], "saturday-morning-classics": ["cartoon", "saturday-morning"],
    "dc-animated": ["kids-wb"], "marvel-animated": ["fox-kids"], "classic-tv": ["tv-land", "classic-sitcom"],
    "tv-land": ["classic-tv", "classic-sitcom"], "nick-at-nite": ["classic-sitcom", "classic-tv"],
    "scifi-channel": ["sci-fi"], "drive-in": ["b-movie", "horror-movie"], "horror-movie": ["drive-in"],
    "b-movie": ["drive-in"], "classic-movie": ["amc", "tcm"], "blockbuster-movie": ["hbo", "cinemax", "showtime"],
    "action-movie": ["cinemax"], "dcom": ["disney-movie"], "hip-hop": ["music-video", "bet"],
    "r-and-b": ["music-video", "bet"], "comic-view": ["bet", "standup"], "standup": ["comedy-central"],
    "sketch-comedy": ["comedy-central"], "real-world": ["mtv"], "mtv-animation": ["mtv"], "trl": ["mtv"],
    "how-to": ["pbs", "hgtv"], "bob-ross": ["how-to", "pbs"], "food-network": ["cooking"], "hgtv": ["home"],
    "animal-planet": ["nature"], "nature": ["animal-planet", "documentary"], "discovery": ["documentary"],
    "tlc": ["documentary"], "history-channel": ["documentary"], "a-and-e": ["documentary"], "biography": ["a-and-e"],
    "tales-from-the-crypt": ["hbo", "kid-horror"], "hbo": ["hbo-original"], "thursday-2000s": ["nbc"],
    "cnn": ["news"], "headline-news": ["news"], "espn": ["sports"], "espn2": ["sports"], "nfl": ["sports", "football"],
    "wrestling-classics": ["wrestling", "wcw", "wcw-nitro", "wwf", "raw"], "qvc": ["shopping"], "hsn": ["shopping"],
    "tv-guide-channel": ["prevue-channel"], "late-show": ["late-night"], "tonight-show": ["late-night"],
    "late-night-conan": ["late-night"], "mst3k": ["scifi-channel"], "star-wars-movie": ["star-wars"],
    "kid-horror": ["snick"], "henson": ["pbs-kids"], "pbs-kids": ["educational-kids"], "nick-jr": ["preschool"],
    "playhouse-disney": ["preschool"], "fx": ["x-files-paranormal"],
    "lifetime": ["tv-movie"], "tv-movie": ["lifetime"], "e-entertainment": ["entertainment-news"],
    "music-video": ["vh1"], "court-tv": ["documentary"], "south-park": ["comedy-central"],
    "daily-show": ["comedy-central"], "game-show": ["cbs"], "soap-opera": ["abc"], "one-saturday-morning": ["disney-channel"],
    "tnbc": ["teen-sitcom"], "scrubs": ["thursday-2000s"],
}
FILLER_KIND = {"C"}
PROMO_JUNK = re.compile(r"\b(fbi warning|warning screen|copyright|screen recording|youtube|legal|notice|open beta|clg video|less clutter|interpol|countdown|logo reveal|test pattern|output \(\d+\)|20(09|1\d|2\d))\b|nbc sports network|olympics", re.I)
KIDS_PROMO = re.compile(r"\b(kids ?wb|kidswb|cartoon network|toonami|nick|nickelodeon|nicktoons|disney|fox kids|abc kids|cn|one saturday morning|cbs kids|pbs kids|playhouse|treehouse|ytv|boomerang)\b", re.I)
ADULT_AD = re.compile(r"\b(adult|hotline|porn|xxx|softcore|sexploitation|emmanuelle|vondel|stripper|centerfold|900[- ]?number|1-900|lingerie|sexy?|playboy|girls gone wild|x-rated|nude|erotic)\b", re.I)
JUNK = re.compile(r"\b(asmr|roleplay|tutorial|secretly|press kit|electronic press|toy fair|raw tour|promos and|reaction|reacts|review|podcast|retrospective|speedrun|let'?s play|gameplay|unboxing|explained|vlog|blooper|trying to|cold war)\b", re.I)
TAG = re.compile(r"^[a-z0-9][a-z0-9-]{0,39}$")
NUM_SPLIT = re.compile(r"\s+[-–—:|]\s+")


def asset_id(identifier, file_name):
    stem = file_name.rsplit(".", 1)[0] if "." in file_name else file_name
    slug = re.sub(r"[^A-Za-z0-9._-]+", "_", stem).strip("_")[:40]
    h = hashlib.sha256(f"{identifier}/{file_name}".encode()).digest()[:4].hex()
    return f"{identifier}:{slug}:{h}"[:128]


def url_of(identifier, file_name):
    q = lambda s: urllib.parse.quote(s, safe="-._~")
    return f"https://archive.org/download/{q(identifier)}/" + "/".join(q(p) for p in file_name.split("/"))


def words(s):
    return [w for w in re.sub(r"[^a-z0-9]+", " ", (s or "").lower()).split() if w not in ("the", "of", "a", "and", "with")]


SEP = re.compile(r"\s+[-–—:|]\s+|\s+(?:s\d{1,2}\s*e\d{1,3}|s\d{1,2}\b|\d{1,2}x\d{1,3}|ep\.?\s*\d+|episode\b|season\b|vol\.?\s*\d+)", re.I)


def series_for(queries, title, item_title):
    # Prefer the show name as written in the file ("TaleSpin - Ep 16 ..." -> "TaleSpin").
    for t in (title, item_title):
        head = SEP.split(t or "", maxsplit=1)[0].strip(" -_.")
        head = re.sub(r"^\d+\s*[-.]?\s*", "", head)
        hw = words(head)
        for q in queries:
            qw = words(q)
            if qw and hw and len(hw) <= len(qw) + 2 and all(any(h == w or (len(w) >= 3 and h.startswith(w)) for h in hw) for w in qw):
                return head
    for q in queries:
        qw = words(q)
        if not qw or any(x.isdigit() and len(x) == 3 for x in qw):
            continue
        for t in (title, item_title):
            tw = words(t)
            if all(any(t2 == w or (len(w) >= 3 and t2.startswith(w)) for t2 in tw) for w in qw):
                return " ".join(w.capitalize() if w not in ("of", "the", "and") else w for w in q.split())
    return None


JUNK_PAREN = re.compile(r"\s*[\(\[][^\)\]]*(?:\d{3,4}p|rip|x26[45]|hevc|aac|ac3|dvd|vhs|web|hdtv|xvid|divx|remux|bluray|uncut|1080|720|480)[^\)\]]*[\)\]]", re.I)


def tidy(title):
    t = JUNK_PAREN.sub("", title)
    t = re.sub(r"\s+", " ", t).strip(" -_")
    return t[:300] or "Untitled"


# Specialty pools: anything found under another pool whose show name matches joins these too
# (Voyager found by the UPN search also airs on the Voyager channel).
CROSS = ["scrubs", "simpsons", "star-trek", "star-trek-tng", "star-trek-ds9", "star-trek-voyager", "star-trek-movie",
         "mst3k", "bob-ross", "south-park", "x-files-paranormal", "thursday-2000s", "disney-afternoon",
         "saturday-morning-classics", "nicktoons-classic", "dc-animated", "marvel-animated", "anime-classics", "kid-horror",
         "henson", "must-see-sitcom", "star-wars", "star-wars-movie", "bond", "kaiju", "fantasy-movie", "disney-movie"]
GENERIC = {"movie", "film", "1990s", "1980s", "kaiju", "007", "star wars", "action movie", "b-movie", "horror movie", "er", "lost", "angel", "popular"}


def anchored(title, q):
    tw = [w for w in words(title) if not w.isdigit()]
    qw = words(q)
    return bool(qw) and len(tw) >= len(qw) and all(tw[k] == w or (len(w) >= 3 and tw[k].startswith(w)) for k, w in enumerate(qw))


def cross_tags(title, item_title, kind):
    out = []
    for tag, k, queries in [(b[0], b[1], b[2]) for b in B if b[0] in CROSS]:
        if (k == "M") != (kind == "M"):
            continue
        if any(q not in GENERIC and (anchored(title, q) or anchored(item_title, q)) for q in queries):
            out.append(tag)
    return out


KIDS_AD = re.compile(r"\b(toys?|cereal|candy|barbie|hot ?wheels|lego|nintendo|sega|game ?boy|playstation|n64|kool-?aid|fruit snacks|gushers|lunchables|happy meal|kids|nickelodeon|nick|cartoon|action figures?|dolls?|mattel|hasbro|kenner|tonka|nerf|super soaker|furby|tamagotchi|pok[eé]mon|power rangers|ninja turtles|tmnt|g\.?i\.? joe|transformers|my little pony|polly pocket|easy-?bake|crayola|play-?doh|trix|lucky charms|frosted flakes|cap'?n crunch|cocoa|cinnamon toast|pop-?tarts|capri sun|sunny ?d|chuck e\.? cheese|fisher-?price|little tikes|spaghettios|oreo|chips ahoy|twinkies?|skittles|starburst|m&m|fruit by the foot|fruit roll|go-?gurt|bagel bites|pizza rolls|ecto ?cooler|toys ?r ?us|kenner|micro machines|teddy ruxpin|cabbage patch|ghostbusters|beanie|yo-?yo|bop it|simon|hungry hungry|mouse ?trap|operation|twister|nickelodeon|fox kids|kids ?wb|saturday morning)\b", re.I)


def build(records):
    assets, by_bucket = {}, {}
    BB = list(B) + list(B2)
    for bi, ident, fname, title, secs, year, item_title, _fmt in records:
        tag, kind, queries = BB[bi][0], BB[bi][1], BB[bi][2]
        aid = asset_id(ident, fname)
        if not re.match(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$", aid) or secs <= 0:
            continue
        if kind == "T":
            ctype = "TRAILER"
        elif kind == "C":
            ctype = "PROMO_BUMPER" if tag in ("promos-90s", "cartoon-network-bumpers") or re.search(r"\b(promo|bumper|bump|station id|ident)\b", title, re.I) else "COMMERCIAL"
        elif kind == "V":
            ctype = "MUSIC_VIDEO"
        elif kind == "M":
            ctype = "MOVIE"
        elif kind == "A":
            ctype = "AIRCHECK_BLOCK" if tag not in ("qvc", "hsn") else "SHOPPING"
        elif kind == "X":
            ctype = "SPECIAL" if secs > 75 * 60 else "EPISODE"
        else:
            ctype = "EPISODE"
        if kind not in ("C", "V") and (JUNK.search(title) or JUNK.search(item_title or "")):
            continue
        if kind in ("C", "T") and ADULT_AD.search(f"{title} {item_title}"):
            continue
        if kind == "C" and PROMO_JUNK.search(f"{title} {item_title}"):
            continue
        tags = [tag] + [t for t in ALIASES.get(tag, []) if t != tag]
        if kind == "C" and tag != "cartoon-network-bumpers":
            # Every commercial airs on regular channels; kid-targeted ones also on the kids' networks.
            if ctype == "COMMERCIAL":
                tags += ["commercials-90s"] + (["ads-kids"] if KIDS_AD.search(f"{title} {item_title}") else [])
            elif KIDS_PROMO.search(f"{title} {item_title}"):
                # Kids-network promos and bumpers stay on the kids' channels.
                tags = [t for t in tags if t != "promos-90s"] + ["promos-kids"]
            else:
                tags += ["promos-90s"]
        if kind in ("E", "M", "X"):
            for ct in cross_tags(title, item_title, kind):
                tags += [ct] + ALIASES.get(ct, [])
        tags = [t for t in dict.fromkeys(tags) if TAG.match(t)]
        if aid in assets:  # same file found for two pools: it joins both
            assets[aid]["tags"] = list(dict.fromkeys(assets[aid]["tags"] + tags))
            continue
        series = series_for(queries, title, item_title) if ctype in ("EPISODE", "SPECIAL") else None
        a = {
            "id": aid, "archiveIdentifier": ident, "fileName": fname, "url": url_of(ident, fname),
            "title": tidy(title), "description": "", "durationMs": int(secs) * 1000, "sourceStartMs": 0,
            "seekable": True, "codec": "h264", "container": "mp4", "yearVerified": False,
            "rightsStatus": "REVIEW_REQUIRED", "provenance": "ORIGINAL_RECORDING",
            "adOrigin": "EMBEDDED_ORIGINAL" if ctype == "AIRCHECK_BLOCK" else "NONE",
            "healthStatus": "UNKNOWN", "contentType": ctype, "tags": tags,
        }
        if year and 1990 <= year <= 2015:
            a["broadcastYear"] = year
        if series:
            a["series"] = series[:200]
        assets[aid] = a
        by_bucket[tag] = by_bucket.get(tag, 0) + secs
    return assets, by_bucket


def main():
    records = json.load(open(sys.argv[1]))
    # Optional second harvest (commercials + trailers), bucket indexes into B2, shifted past B.
    if len(sys.argv) > 2:
        extra = json.load(open(sys.argv[2]))
        records += [[len(B) + r[0]] + r[1:] for r in extra]
    assets, by_bucket = build(records)
    path = os.path.join(ROOT, "app/src/main/assets/default_manifest.json")
    manifest = json.load(open(os.path.join(ROOT, "manifest/channel-surf-manifest.json")))
    manifest["assets"] = sorted(assets.values(), key=lambda a: a["id"])
    # Compact on purpose: thousands of assets.
    head = json.dumps({k: manifest[k] for k in ("schemaVersion", "revision")})[:-1]
    rest = json.dumps({k: v for k, v in manifest.items() if k not in ("schemaVersion", "revision")}, separators=(",", ":"))[1:]
    text = head + "," + rest
    with open(path, "w") as f:
        f.write(text)
    print(f"{len(assets)} assets, {len(text) / 1e6:.1f} MB -> {path}")
    # Channel coverage report
    for c in manifest["channels"]:
        tags = set(c.get("defaultTags", [])) | {t for d in c.get("dayparts", []) for t in d["tags"]}
        own = sum(a["durationMs"] for a in assets.values() if a["contentType"] not in ("COMMERCIAL", "PROMO_BUMPER") and set(a["tags"]) & tags)
        fb = sum(a["durationMs"] for a in assets.values() if a["contentType"] not in ("COMMERCIAL", "PROMO_BUMPER") and set(a["tags"]) & set(c.get("fallbackTags", [])))
        print(f"{c['number']:>3} {c['displayName'][:26]:26} own {own / 3.6e6:7.1f} h   borrow {fb / 3.6e6:7.1f} h")


if __name__ == "__main__":
    main()
