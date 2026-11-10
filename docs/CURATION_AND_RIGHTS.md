# Curation, discovery and rights

**Default: automatic.** The discovery job (daily, plus immediately on a fresh install and on *Find new programs
now*) runs each channel's searches, performs the technical validation below, classifies and tags each find for the
block that searched for it, and — with *Air new finds without review* on (the default) — airs it without a manual
sign-off. Items whose detected year is outside 1990–2005 are skipped and remembered so they aren't fetched again.
Deleted or taken-down items are never re-added.

**Manual mode** (turn the setting off) restores the full review pipeline described below: every recording needs
rights cleared and a verified broadcast year before it can air.

## 1. Discovery

*Setup → Curation → Discover.* Search uses the documented `advancedsearch.php` endpoint restricted to
`mediatype:(movies)`; presets are only starting points (VHS airchecks, commercials, Saturday morning, local news …).
Because Archive tagging is inconsistent, operators can also paste any identifier or `archive.org/details/…` link.

## 2. Technical validation (automatic)

1. `/metadata/{identifier}` is fetched (cached 6 h; stale cache used offline). Missing (`{}`) or dark items are rejected.
2. Files are classified. Accepted: `h.264`, `MPEG4`, `512Kb MPEG4`, `.mp4/.m4v`, `WebM`, and `.m3u8` only if the item
   actually contains one (HLS URLs are never invented). Rejected: metadata, thumbnails, subtitles (kept separately as
   caption candidates), Ogg Theora, MPEG-1/2, AVI, WMV, QuickTime and anything unknown.
3. Up to four candidates are probed with a 2-byte ranged GET. A file must answer `206` with `Content-Range` to be
   seekable (required to join a program mid-way). Up to two other reachable renditions are kept as verified alternates.
4. The chosen file is **played on the device**: prepared in a muted ExoPlayer, its decoded duration recorded as
   authoritative, then seeked to the midpoint to prove seeking works. Failure leaves it in review, marked degraded.

## 3. Editorial review (operator)

The item enters the **Review queue** with `REVIEW_REQUIRED` rights and an *unverified* year, regardless of metadata.

* **Broadcast year.** Upload/added dates are never used. The app lists clues from `year`, `date`, title, description
  and subjects. The operator confirms from on-tape evidence (station IDs, slates, ad content, guide data) and ticks
  *Verified*. Only 1990–2005 can air; 1990–1999 is the preferred range.
* **Guide title.** Be honest: a six-hour tape is "1994 Saturday Morning VHS Block", not invented episode names.
  Optional segments (`COMMERCIAL`, `STATION_ID` …) annotate long tapes.
* **Provenance.** *Original off-air* vs *Reconstructed*; and whether commercials are original to the tape, inserted
  from separately archived reels, or absent. The info panel shows this to viewers, so reconstructed blocks are never
  presented as unaltered recordings. Embedded commercials are never detected or removed.
* **Rights.** Choose `CLEARED` only with written evidence in *Rights notes* (license, permission, public-domain basis,
  contract reference). The app refuses to clear without notes. An Archive `licenseurl` is recorded as a hint, not proof.
* **Skip tape leader** sets `sourceStartMs` so the channel doesn't air blank leader.

## 4. Scheduling

Approved items can be added to a channel's ordered schedule or its fallback pool (used for substitution when a file
fails on a device). The **Lineup** tab reorders, removes, and enables/hides channels. Every change creates a new
catalog revision and rebuilds schedules immediately; channels keep their epochs, so unrelated channels don't move.

## 5. Takedown / removal workflow

1. In the asset's card, enter the reason / request reference and press **Take down item**.
2. Effects, immediately: every file of that Archive identifier is set `BLOCKED`; it's removed from all channels and
   fallback pools; if on the air it's stopped and the slot is re-resolved; the takedown is logged (`takedowns` table).
3. Persistence: blocked identifiers stay blocked even if a later manifest (file or server) includes them.
4. *Lift* removes the takedown record; assets remain `BLOCKED` until someone re-reviews them.

For a published lineup, also remove the asset from the server manifest and republish.

## Caching policy

Metadata, search results and schedules are cached. Video is streamed from the Archive and never stored (beyond
ExoPlayer's bounded in-memory buffer).

## Disclaimer

This software is an implementation of a specification, not legal advice and not a representation that any recording
is available or reusable. Verify permissions and attribution before distributing a lineup.
