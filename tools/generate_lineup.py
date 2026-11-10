#!/usr/bin/env python3
"""
Generates the Channel Surf cable lineup manifest (manifest/channel-surf-manifest.json and the copy bundled in the
app). The lineup models a typical late-1990s / early-2000s US analog cable system (exact channel numbers varied by
market). Dayparts recreate era programming blocks; minutes are local to the channel's feed (Eastern) and may run
past midnight up to 30:00 (6 AM), ISO weekdays 1=Mon .. 7=Sun.

The manifest carries NO media. Recordings arrive through discovery/curation and join channels by tag.

    python3 tools/generate_lineup.py
"""
import json, os, re, sys

REVISION = "cable-lineup-2026-11-10"

ALL = [1, 2, 3, 4, 5, 6, 7]
WD = [1, 2, 3, 4, 5]
SAT, SUN, FRI = [6], [7], [5]
SUN_THU = [7, 1, 2, 3, 4]
EPI, MOV = ["EPISODE"], ["MOVIE"]


def t(hhmm):
    h, m = hhmm.split(":")
    return int(h) * 60 + int(m)


def dp(id, title, days, start, end, tags, prefer=None):
    d = {"id": id, "title": title, "days": days, "startMinute": t(start), "endMinute": t(end), "tags": tags}
    if prefer:
        d["preferTypes"] = prefer
    return d


def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


# number, name, category, color, default tags, dayparts, extra discovery queries
C = [
    (1, "Prevue Channel", "GUIDE", "#7FA7E0", ["prevue-channel", "tv-guide-channel"], [], ["prevue channel", "prevue guide 1995", "tv guide channel 2000"]),
    (2, "CBS", "NETWORK", "#3A6FD8", ["cbs", "sitcom", "drama"], [
        dp("this-morning", "CBS This Morning", WD, "07:00", "09:00", ["cbs-news", "morning-news"]),
        dp("daytime", "Daytime", WD, "10:00", "16:00", ["game-show", "soap-opera"], EPI),
        dp("evening-news", "CBS Evening News", WD, "18:30", "19:00", ["cbs-news", "network-news"]),
        dp("prime", "CBS Prime Time", ALL, "20:00", "23:00", ["cbs", "sitcom", "drama"], EPI),
        dp("late-show", "Late Show", WD, "23:35", "24:35", ["late-show", "late-night"], EPI),
        dp("saturday-morning", "CBS Saturday Morning", SAT, "07:00", "12:00", ["cbs-kids", "saturday-morning"], EPI),
    ], ["cbs 1996 broadcast", "cbs commercials 1990s", "late show letterman 1990s"]),
    (3, "PBS", "PUBLIC TV", "#E0E0E0", ["pbs", "documentary", "how-to"], [
        dp("pbs-kids", "PBS Kids", ALL, "06:00", "12:00", ["pbs-kids", "sesame-street", "educational-kids"], EPI),
        dp("newshour", "The NewsHour", WD, "18:00", "19:00", ["pbs-news"]),
        dp("prime", "PBS Prime Time", ALL, "20:00", "23:00", ["pbs", "documentary", "nova", "masterpiece"], ["EPISODE", "SPECIAL"]),
    ], ["pbs 1994", "pbs kids 1999", "pbs pledge break", "reading rainbow", "mister rogers neighborhood"]),
    (4, "NBC", "NETWORK", "#F2C230", ["nbc", "sitcom", "drama"], [
        dp("today", "Today", WD, "07:00", "10:00", ["today-show", "morning-news"]),
        dp("tnbc", "TNBC", SAT, "08:00", "12:00", ["tnbc", "teen-sitcom", "saved-by-the-bell"], EPI),
        dp("prime", "NBC Prime Time", [1, 2, 3, 5, 6, 7], "20:00", "23:00", ["nbc", "sitcom", "drama"], EPI),
        dp("must-see-tv", "Must See TV", [4], "20:00", "22:00", ["must-see-tv", "nbc-sitcom"], EPI),
        dp("thursday-drama", "NBC Thursday Drama", [4], "22:00", "23:00", ["nbc-drama", "er"], EPI),
        dp("tonight", "The Tonight Show", WD, "23:35", "24:35", ["tonight-show", "late-night"], EPI),
        dp("late-night", "Late Night", WD, "24:35", "25:35", ["late-night-conan", "late-night"], EPI),
        dp("snl", "Saturday Night Live", SAT, "23:30", "25:00", ["snl", "sketch-comedy"], EPI),
    ], ["nbc 1997 broadcast", "must see tv nbc", "tonight show 1995", "saturday night live 1990s"]),
    (5, "FOX", "NETWORK", "#C0392B", ["fox", "sitcom", "animated-sitcom"], [
        dp("fox-kids", "Fox Kids", WD, "15:00", "17:00", ["fox-kids", "cartoon"], EPI),
        dp("fox-kids-sat", "Fox Kids Saturday", SAT, "08:00", "12:00", ["fox-kids", "cartoon", "saturday-morning"], EPI),
        dp("nfl", "NFL on FOX", SUN, "13:00", "16:00", ["nfl", "football"]),
        dp("prime", "FOX Prime Time", ALL, "20:00", "22:00", ["fox", "animated-sitcom", "sitcom", "x-files"], EPI),
        dp("madtv", "MADtv", SAT, "23:00", "24:00", ["madtv", "sketch-comedy"], EPI),
    ], ["fox kids 1995", "fox 1998 broadcast", "fox kids commercials"]),
    (6, "UPN", "NETWORK", "#2E86C1", ["upn", "syndicated", "sitcom"], [
        dp("upn-kids", "UPN Kids", SUN, "08:00", "11:00", ["upn-kids", "cartoon"], EPI),
        dp("prime", "UPN Prime Time", ALL, "20:00", "22:00", ["upn", "star-trek", "sci-fi"], EPI),
    ], ["upn 1997", "star trek voyager upn"]),
    (7, "ABC", "NETWORK", "#1B1B1B", ["abc", "sitcom", "drama"], [
        dp("gma", "Good Morning America", WD, "07:00", "09:00", ["good-morning-america", "morning-news"]),
        dp("one-saturday-morning", "One Saturday Morning", SAT, "08:00", "12:00", ["one-saturday-morning", "disney-cartoon", "cartoon"], EPI),
        dp("tgif", "TGIF", FRI, "20:00", "22:00", ["tgif", "family-sitcom"], EPI),
        dp("20-20", "20/20", FRI, "22:00", "23:00", ["news-magazine", "20-20"]),
        dp("prime", "ABC Prime Time", [1, 2, 3, 4, 6, 7], "20:00", "23:00", ["abc", "drama", "sitcom"], EPI),
        dp("nightline", "Nightline", WD, "23:35", "24:05", ["nightline", "network-news"]),
    ], ["abc tgif", "one saturday morning abc", "abc 1993 broadcast"]),
    (8, "The WB", "NETWORK", "#8E44AD", ["the-wb", "teen-drama", "sitcom"], [
        dp("kids-wb", "Kids' WB!", WD, "15:00", "17:00", ["kids-wb", "cartoon"], EPI),
        dp("kids-wb-sat", "Kids' WB! Saturday", SAT, "08:00", "12:00", ["kids-wb", "cartoon", "saturday-morning"], EPI),
        dp("prime", "WB Prime Time", ALL, "20:00", "22:00", ["the-wb", "teen-drama"], EPI),
    ], ["kids wb 1999", "the wb 2001 broadcast"]),
    (9, "Channel 9 Independent", "LOCAL", "#E07B39", ["syndicated", "classic-tv", "local-tv"], [
        dp("afternoon-cartoons", "Afternoon Cartoons", WD, "14:00", "17:00", ["syndicated-cartoon", "cartoon"], EPI),
        dp("movie", "Channel 9 Movie", ALL, "20:00", "22:00", ["movie", "tv-movie"], MOV),
        dp("late-movie", "The Late Movie", ALL, "23:30", "25:30", ["b-movie", "movie"], MOV),
    ], ["independent station 1992 broadcast", "local tv 1990s commercials", "syndicated cartoons 1991"]),
    (10, "Public Access", "COMMUNITY", "#A8DADC", ["public-access", "local-tv"], [], ["public access television 199*", "cable access show 1990s"]),
    (11, "C-SPAN", "PUBLIC AFFAIRS", "#5D6D7E", ["c-span", "politics"], [], ["c-span 1995", "c-span 2000"]),
    (12, "QVC", "SHOPPING", "#16A085", ["qvc", "shopping"], [], ["qvc 1996", "qvc 2002"]),
    (13, "Home Shopping Network", "SHOPPING", "#D35400", ["hsn", "shopping", "infomercial"], [], ["home shopping network 1990s", "infomercial 1990s"]),
    (14, "The Weather Channel", "WEATHER", "#48CAE4", ["weather-channel", "weather"], [], ["weather channel 1995", "weather channel local on the 8s"]),
    (15, "CNN", "NEWS", "#C0392B", ["cnn", "news"], [
        dp("larry-king", "Larry King Live", WD, "21:00", "22:00", ["larry-king", "talk"]),
    ], ["cnn 1991 broadcast", "cnn 2000"]),
    (16, "Headline News", "NEWS", "#A93226", ["headline-news", "news"], [], ["headline news 1995", "cnn headline news 1990s"]),
    (17, "ESPN", "SPORTS", "#E74C3C", ["espn", "sports"], [
        dp("sportscenter", "SportsCenter", ALL, "23:00", "24:00", ["sportscenter"]),
    ], ["espn 1996", "sportscenter 1990s"]),
    (18, "ESPN2", "SPORTS", "#CB4335", ["espn2", "extreme-sports", "sports"], [], ["espn2 1994", "x games 1990s"]),
    (19, "TBS", "SUPERSTATION", "#2471A3", ["tbs", "classic-sitcom", "sitcom", "movie"], [
        dp("wcw-saturday", "WCW Saturday Night", SAT, "18:05", "20:05", ["wcw", "wrestling"]),
        dp("dinner-and-a-movie", "Dinner and a Movie", FRI, "20:05", "23:05", ["dinner-and-a-movie", "movie"], MOV),
    ], ["tbs superstation 1995", "wcw saturday night"]),
    (20, "TNT", "MOVIES & DRAMA", "#D4AC0D", ["tnt", "movie", "drama"], [
        dp("nitro", "WCW Monday Nitro", [1], "20:00", "22:00", ["wcw-nitro", "wrestling"]),
        dp("tnt-movie", "TNT Movie", [2, 3, 4, 5, 6, 7], "20:00", "22:00", ["tnt", "movie"], MOV),
    ], ["tnt 1997 broadcast", "wcw monday nitro"]),
    (21, "USA Network", "ENTERTAINMENT", "#1F618D", ["usa-network", "drama", "syndicated"], [
        dp("cartoon-express", "USA Cartoon Express", WD, "07:00", "10:00", ["usa-cartoon-express", "cartoon"], EPI),
        dp("raw", "WWF Monday Night Raw", [1], "21:00", "23:00", ["wwf", "raw", "wrestling"]),
        dp("up-all-night", "USA Up All Night", [5, 6], "23:00", "29:00", ["up-all-night", "b-movie"], MOV),
    ], ["usa network 1993", "usa cartoon express", "usa up all night"]),
    (22, "A&E", "ARTS & ENTERTAINMENT", "#7D3C98", ["a-and-e", "documentary", "drama", "mystery"], [
        dp("biography", "Biography", ALL, "20:00", "21:00", ["biography"]),
    ], ["a&e 1995 broadcast", "biography a&e 1990s"]),
    (23, "Discovery Channel", "DOCUMENTARY", "#1E8449", ["discovery", "documentary", "nature"], [], ["discovery channel 1996", "shark week 1990s"]),
    (24, "TLC", "LEARNING", "#AF601A", ["tlc", "documentary", "home"], [
        dp("ready-set-learn", "Ready Set Learn", WD, "06:00", "12:00", ["ready-set-learn", "educational-kids"], EPI),
    ], ["the learning channel 1995", "tlc 1999"]),
    (25, "The History Channel", "HISTORY", "#7B6F4B", ["history-channel", "documentary", "history"], [], ["history channel 1997"]),
    (26, "Nickelodeon", "KIDS", "#FF7F00", ["nickelodeon", "kids"], [
        dp("nick-jr", "Nick Jr.", WD, "09:00", "14:00", ["nick-jr", "preschool"], EPI),
        dp("nicktoons", "Nicktoons", ALL, "14:00", "20:00", ["nicktoons", "nickelodeon", "cartoon"], EPI),
        dp("snick", "SNICK", SAT, "20:00", "22:00", ["snick", "nickelodeon"], EPI),
        dp("nick-at-nite", "Nick at Nite", [1, 2, 3, 4, 5, 7], "20:00", "30:00", ["nick-at-nite", "classic-sitcom", "classic-tv"], EPI),
        dp("nick-at-nite-sat", "Nick at Nite", SAT, "22:00", "30:00", ["nick-at-nite", "classic-sitcom", "classic-tv"], EPI),
    ], ["nickelodeon 1994", "snick 1995", "nick at nite 1990s", "nickelodeon commercials 1990s"]),
    (27, "Cartoon Network", "CARTOONS", "#000000", ["cartoon-network", "cartoon", "hanna-barbera", "looney-tunes"], [
        dp("boomerang", "Boomerang", [6, 7], "06:00", "08:00", ["boomerang", "classic-cartoon", "hanna-barbera"], EPI),
        dp("toonami", "Toonami", WD, "16:00", "18:00", ["toonami", "anime"], EPI),
        dp("cartoon-cartoon-fridays", "Cartoon Cartoon Fridays", FRI, "19:00", "21:00", ["cartoon-cartoons", "cartoon-network-original"], EPI),
        dp("toonami-saturday", "Toonami Saturday Night", SAT, "19:00", "23:00", ["toonami", "anime"], EPI),
        dp("adult-swim", "Adult Swim", SUN_THU, "23:00", "29:00", ["adult-swim"], EPI),
    ], ["cartoon network 1998", "toonami", "toonami 2000", "adult swim 2003", "cartoon network bumpers", "cartoon cartoon fridays"]),
    (28, "Disney Channel", "FAMILY", "#2874A6", ["disney-channel", "disney", "kids"], [
        dp("playhouse-disney", "Playhouse Disney", WD, "07:00", "12:00", ["playhouse-disney", "preschool"], EPI),
        dp("dcom", "Disney Channel Original Movie", FRI, "19:00", "21:00", ["dcom", "disney-movie"], MOV),
        dp("zoog-disney", "Zoog Disney", [6, 7], "18:00", "21:00", ["zoog-disney", "disney-channel-original"], EPI),
    ], ["disney channel 1999", "zoog disney", "disney channel 1990s commercials"]),
    (29, "Comedy Central", "COMEDY", "#F5B041", ["comedy-central", "comedy", "sketch-comedy", "standup"], [
        dp("mst3k", "Mystery Science Theater 3000", SAT, "10:00", "12:00", ["mst3k"], EPI),
        dp("snl-classics", "Saturday Night Live Classics", SAT, "20:00", "22:00", ["snl", "sketch-comedy"], EPI),
        dp("south-park", "South Park", [3], "22:00", "22:30", ["south-park"], EPI),
        dp("daily-show", "The Daily Show", WD, "23:00", "23:30", ["daily-show"], EPI),
        dp("standup", "Comedy Central Presents", ALL, "24:00", "26:00", ["standup", "comedy-central-presents"]),
    ], ["comedy central 1997", "comedy central 2002", "comedy central presents"]),
    (30, "MTV", "MUSIC", "#E04848", ["mtv", "music-video", "pop"], [
        dp("trl", "Total Request Live", WD, "15:30", "16:30", ["trl", "music-video"]),
        dp("yo-mtv-raps", "Yo! MTV Raps", WD, "16:30", "17:30", ["yo-mtv-raps", "hip-hop"]),
        dp("unplugged", "MTV Unplugged", FRI, "21:00", "22:00", ["unplugged", "live-music"]),
        dp("real-world", "The Real World", ALL, "22:00", "23:00", ["real-world", "road-rules", "mtv-original"], EPI),
        dp("mtv-animation", "MTV Animation", ALL, "23:00", "23:30", ["beavis-and-butthead", "daria", "mtv-animation"], EPI),
        dp("headbangers-ball", "Headbangers Ball", SAT, "24:00", "27:00", ["headbangers-ball", "metal"]),
        dp("120-minutes", "120 Minutes", SUN, "24:00", "26:00", ["120-minutes", "alternative"]),
    ], ["mtv 1992 broadcast", "mtv 1999 broadcast", "mtv commercials 1990s", "total request live", "120 minutes mtv"]),
    (31, "VH1", "MUSIC", "#9B59B6", ["vh1", "music-video", "classic-rock", "adult-contemporary"], [
        dp("countdown", "VH1 Top 20 Countdown", SAT, "10:00", "12:00", ["vh1-countdown"]),
        dp("pop-up-video", "Pop-Up Video", ALL, "19:00", "20:00", ["pop-up-video"]),
        dp("behind-the-music", "Behind the Music", ALL, "21:00", "22:00", ["behind-the-music"]),
        dp("storytellers", "VH1 Storytellers", SAT, "22:00", "23:00", ["storytellers", "live-music"]),
    ], ["vh1 1997 broadcast", "pop up video vh1", "behind the music"]),
    (32, "BET", "MUSIC", "#B9770E", ["bet", "r-and-b", "hip-hop", "music-video"], [
        dp("rap-city", "Rap City", WD, "17:00", "18:00", ["rap-city", "hip-hop"]),
        dp("video-soul", "Video Soul", WD, "18:00", "19:00", ["video-soul", "r-and-b"]),
        dp("comic-view", "BET Comic View", ALL, "22:00", "23:00", ["comic-view", "standup"]),
    ], ["bet 1995 broadcast", "rap city bet"]),
    (33, "CMT", "COUNTRY", "#A04000", ["cmt", "country", "music-video"], [], ["cmt 1996", "country music television 1990s"]),
    (34, "Sci-Fi Channel", "SCI-FI", "#4FD1A5", ["scifi-channel", "sci-fi", "classic-scifi"], [
        dp("saturday-anime", "Saturday Anime", SAT, "09:00", "11:00", ["anime"], EPI),
        dp("mst3k", "Mystery Science Theater 3000", SAT, "11:00", "13:00", ["mst3k"], EPI),
        dp("prime", "Sci-Fi Prime", ALL, "20:00", "22:00", ["sci-fi", "star-trek", "x-files"], EPI),
        dp("creature-features", "Creature Features", [5, 6], "23:00", "25:00", ["b-movie", "horror-movie"], MOV),
    ], ["sci-fi channel 1995", "scifi channel 2001"]),
    (35, "TV Land", "CLASSIC TV", "#F1C40F", ["tv-land", "classic-tv", "classic-sitcom"], [], ["tv land 1997", "tv land retromercials"]),
    (36, "E!", "ENTERTAINMENT", "#EC7063", ["e-entertainment", "entertainment-news"], [
        dp("talk-soup", "Talk Soup", WD, "19:00", "19:30", ["talk-soup"]),
        dp("true-hollywood-story", "E! True Hollywood Story", ALL, "21:00", "22:00", ["true-hollywood-story"]),
    ], ["e! entertainment television 1996", "talk soup"]),
    (37, "Lifetime", "WOMEN", "#C0392B", ["lifetime", "drama"], [
        dp("lifetime-movie", "Lifetime Movie", ALL, "20:00", "22:00", ["lifetime", "tv-movie"], MOV),
    ], ["lifetime television 1995"]),
    (38, "Food Network", "FOOD", "#E67E22", ["food-network", "cooking"], [], ["food network 1997", "tv food network"]),
    (39, "HGTV", "HOME", "#27AE60", ["hgtv", "home", "gardening"], [], ["hgtv 1998"]),
    (40, "AMC", "CLASSIC MOVIES", "#B03A2E", ["amc", "classic-movie", "movie"], [
        dp("amc-movies", "American Movie Classics", ALL, "06:00", "30:00", ["amc", "classic-movie", "movie"], MOV),
    ], ["american movie classics 1995", "amc 1990s"]),
    (41, "TCM", "CLASSIC MOVIES", "#5B2C6F", ["tcm", "classic-movie"], [
        dp("tcm-movies", "TCM Presents", ALL, "06:00", "30:00", ["tcm", "classic-movie", "movie"], MOV),
    ], ["turner classic movies 1996"]),
    (42, "FX", "ENTERTAINMENT", "#212F3D", ["fx", "syndicated", "sitcom", "drama"], [
        dp("fx-movie", "FX Movie", [5, 6], "20:00", "22:00", ["fx", "movie"], MOV),
    ], ["fx network 1995", "fx 2002"]),
    (43, "Court TV", "TRIALS", "#566573", ["court-tv"], [], ["court tv 1995"]),
    (44, "Animal Planet", "ANIMALS", "#229954", ["animal-planet", "nature"], [], ["animal planet 1997"]),
    (45, "HBO", "PREMIUM", "#17202A", ["hbo", "movie", "hbo-original"], [
        dp("feature", "HBO Feature Presentation", ALL, "20:00", "22:00", ["hbo", "movie"], MOV),
        dp("tales-from-the-crypt", "Tales from the Crypt", SAT, "22:00", "22:30", ["tales-from-the-crypt"], EPI),
        dp("hbo-comedy", "HBO Comedy Hour", FRI, "23:00", "24:00", ["hbo-comedy", "standup"]),
    ], ["hbo 1993", "hbo feature presentation", "hbo intro 1990s"]),
    (46, "Cinemax", "PREMIUM", "#922B21", ["cinemax", "movie", "action-movie"], [
        dp("cinemax-movie", "Cinemax Movie", ALL, "20:00", "24:00", ["cinemax", "movie", "action-movie"], MOV),
    ], ["cinemax 1990s"]),
    (47, "Showtime", "PREMIUM", "#A93226", ["showtime", "movie", "showtime-original"], [
        dp("showtime-movie", "Showtime Movie", ALL, "20:00", "22:00", ["showtime", "movie"], MOV),
    ], ["showtime 1990s"]),
    # ---- Specialty channels (whole-day themes; any year) ----
    (48, "The Bridge", "STAR TREK 24/7", "#3F7FBF", ["star-trek", "star-trek-tng", "star-trek-ds9", "star-trek-voyager"], [
        dp("trek-movie", "Star Trek Movie Night", [5, 6], "20:00", "22:30", ["star-trek-movie"], MOV),
    ], ["star trek next generation", "star trek deep space nine", "star trek voyager", "star trek original series", "star trek enterprise"]),
    (49, "Galaxy Far Away", "STAR WARS 24/7", "#FFE81F", ["star-wars"], [
        dp("saga", "The Saga", ALL, "19:00", "25:00", ["star-wars-movie"], MOV),
    ], ["star wars", "star wars clone wars", "star wars droids ewoks cartoon"]),
    (50, "Sacred Heart", "SCRUBS 24/7", "#4CB5AE", ["scrubs"], [], ["scrubs"]),
    (51, "The Disney Vault", "DISNEY MOVIES", "#1E3F8F", ["disney-movie"], [], ["disney movie", "walt disney animated", "disney channel original movie"]),
    (52, "Disney Afternoon", "CARTOONS", "#E8483B", ["disney-afternoon"], [], ["ducktales", "darkwing duck", "gargoyles", "talespin", "chip n dale rescue rangers", "goof troop"]),
    (53, "Springfield", "SIMPSONS 24/7", "#FFD90F", ["simpsons"], [], ["the simpsons"]),
    (54, "Must See 24/7", "SITCOMS", "#9B2335", ["must-see-sitcom"], [], ["seinfeld", "friends", "frasier", "cheers", "fresh prince"]),
    (55, "The Truth Is Out There", "PARANORMAL", "#2E4A2E", ["x-files-paranormal"], [], ["x-files", "twilight zone", "outer limits", "unsolved mysteries", "in search of"]),
    (56, "Saturday Morning", "CARTOONS 24/7", "#FF6F3C", ["saturday-morning-classics"], [], ["he-man", "transformers", "g.i. joe", "teenage mutant ninja turtles", "thundercats", "real ghostbusters"]),
    (57, "Anime 2000", "ANIME", "#E63973", ["anime-classics"], [], ["dragon ball z", "sailor moon", "pokemon", "cowboy bebop", "yu yu hakusho", "gundam wing"]),
    (58, "Midnight Toonami", "TOONAMI & ADULT SWIM", "#1B998B", ["toonami-adult-swim"], [], ["toonami", "adult swim", "aqua teen hunger force", "space ghost coast to coast"]),
    (59, "Monday Night Wars", "WRESTLING", "#B22222", ["wrestling-classics"], [], ["wcw nitro", "wwf raw", "ecw", "wcw", "wwf"]),
    (60, "Satellite of Love", "MST3K 24/7", "#7A4FBF", ["mst3k"], [], ["mystery science theater 3000", "mst3k"]),
    (61, "Drive-In", "B-MOVIES & HORROR", "#5A1E1E", ["drive-in"], [], ["horror movie", "b-movie", "sci-fi movie 1950s"]),
    (62, "Gotham", "DC ANIMATED", "#2B2D42", ["dc-animated"], [], ["batman animated series", "superman animated series", "justice league", "batman beyond"]),
    (63, "Marvel Toons", "MARVEL ANIMATED", "#C1121F", ["marvel-animated"], [], ["x-men animated series", "spider-man animated series", "fantastic four animated", "iron man animated"]),
    (64, "Nicktoons 24", "NICKTOONS", "#FF7F00", ["nicktoons-classic"], [], ["rugrats", "ren and stimpy", "rocko's modern life", "hey arnold", "spongebob", "doug nickelodeon"]),
    (65, "Midnight Society", "SPOOKY KIDS TV", "#3D2C8D", ["kid-horror"], [], ["are you afraid of the dark", "goosebumps", "eerie indiana", "tales from the crypt"]),
    (66, "Happy Little Trees", "BOB ROSS 24/7", "#5B8C5A", ["bob-ross"], [], ["joy of painting bob ross"]),
    (67, "Muppet Theater", "JIM HENSON", "#3BB273", ["henson"], [], ["muppet show", "fraggle rock", "dinosaurs tv series", "muppet babies"]),
    (68, "Blockbuster Night", "80s & 90s MOVIES", "#003F87", ["blockbuster-movie"], [], ["1990s movie", "1980s movie", "action movie"]),
    (69, "Agent 007", "JAMES BOND", "#8A8D91", ["bond"], [], ["james bond 007"]),
    (70, "Kaiju Theater", "MONSTER MOVIES", "#4F772D", ["kaiju"], [], ["godzilla", "gamera", "kaiju"]),
    (71, "Thursday Night 2000s", "SITCOMS", "#6A4C93", ["thursday-2000s"], [], ["the office", "parks and recreation", "30 rock", "community", "my name is earl"]),
    (72, "TechTV / G4", "GAMES & TECH", "#00A6ED", ["g4-techtv"], [], ["g4 techtv", "x-play", "screen savers", "video game commercials"]),
    (73, "Middle-earth & Hogwarts", "FANTASY MOVIES", "#7F5539", ["fantasy-movie"], [], ["lord of the rings", "harry potter", "fantasy movie"]),
    (74, "Enterprise-D", "STAR TREK: TNG", "#B5A642", ["star-trek-tng"], [], ["star trek the next generation", "star trek tng"]),
    (76, "Voyager", "STAR TREK: VOYAGER", "#3B5B92", ["star-trek-voyager"], [], ["star trek voyager"]),
    (77, "Trek Cinema", "STAR TREK FILMS", "#1C1C3C", ["star-trek-movie"], [], ["star trek the motion picture", "star trek wrath of khan", "star trek first contact"]),
    (99, "Classic Commercials", "COMMERCIALS", "#FF9F1C", ["commercial-reels", "commercials-90s"], [], ["1990s commercials", "commercials 1995", "commercial compilation vhs"]),
]

FILLER = ["commercials-90s", "station-id", "promos-90s"]
# Kids' networks get the toy/cereal/candy spots; premium channels and PBS ran no commercials at all
# (just promos and, on the movie channels, trailers between features).
KIDS = {26, 27, 28, 52, 56, 62, 63, 64, 65, 67}
COMMERCIAL_FREE = {3, 40, 41, 45, 46, 47, 66}
MOVIE_CHANNELS = {40, 41, 45, 46, 47}


def filler_for(n):
    if n in KIDS:
        return ["ads-kids", "station-id", "promos-kids"]
    if n in COMMERCIAL_FREE:
        return ["commercial-free", "station-id", "promos-90s"] + (["movie-trailers"] if n in MOVIE_CHANNELS else ["pbs-promos"])
    return FILLER

# When a channel has nothing of its own for a block, it borrows from these related pools instead of going
# off the air (like a real station running a syndicated rerun).
FALLBACK = {
    1: ["commercials-90s", "tv-guide-channel"], 2: ["must-see-sitcom", "sitcom", "drama"], 3: ["documentary", "bob-ross", "henson"],
    4: ["must-see-sitcom", "sitcom"], 5: ["simpsons", "x-files-paranormal", "animated-sitcom"], 6: ["star-trek", "syndicated"],
    7: ["sitcom", "tgif", "drama"], 8: ["teen-drama", "sitcom"], 9: ["drive-in", "syndicated-cartoon", "b-movie"],
    10: ["bob-ross", "local-tv"], 11: ["documentary"], 12: ["shopping", "hsn"], 13: ["shopping", "qvc"],
    14: ["weather", "documentary"], 15: ["news", "headline-news"], 16: ["news", "cnn"], 17: ["sports", "espn2", "wrestling-classics"],
    18: ["sports", "espn", "wrestling-classics"], 19: ["sitcom", "classic-sitcom", "movie", "wrestling-classics"],
    20: ["movie", "drama", "wrestling-classics"], 21: ["drama", "syndicated", "cartoon"], 22: ["documentary", "x-files-paranormal"],
    23: ["documentary", "nature"], 24: ["documentary", "home"], 25: ["documentary", "x-files-paranormal"],
    26: ["nicktoons-classic", "kid-horror", "classic-sitcom"], 27: ["toonami-adult-swim", "anime-classics", "dc-animated", "saturday-morning-classics"],
    28: ["disney-afternoon", "disney-movie"], 29: ["sketch-comedy", "standup", "mst3k", "simpsons"], 30: ["music-video", "mtv-animation"],
    31: ["music-video", "behind-the-music"], 32: ["music-video", "hip-hop"], 33: ["music-video"], 34: ["star-trek", "x-files-paranormal", "kaiju", "drive-in"],
    35: ["classic-sitcom", "classic-tv", "must-see-sitcom"], 36: ["entertainment-news", "documentary"], 37: ["tv-movie", "drama", "movie"],
    38: ["cooking", "home"], 39: ["home", "cooking"], 40: ["classic-movie", "movie", "drive-in"], 41: ["classic-movie", "movie"],
    42: ["simpsons", "x-files-paranormal", "movie"], 43: ["documentary", "news"], 44: ["nature", "documentary"],
    45: ["blockbuster-movie", "movie"], 46: ["blockbuster-movie", "action-movie", "movie"], 47: ["blockbuster-movie", "movie"],
    48: ["sci-fi"], 49: ["star-wars-movie"], 50: ["must-see-sitcom", "thursday-2000s"], 51: ["disney-afternoon"],
    52: ["disney-movie", "saturday-morning-classics"], 53: ["animated-sitcom"], 54: ["thursday-2000s", "sitcom"],
    55: ["sci-fi"], 56: ["cartoon", "marvel-animated"], 57: ["toonami"], 58: ["anime-classics", "toonami", "adult-swim"],
    59: ["wrestling"], 60: ["drive-in"], 61: ["kaiju", "b-movie", "horror-movie"], 62: ["marvel-animated"], 63: ["dc-animated"],
    64: ["nicktoons", "nickelodeon"], 65: ["x-files-paranormal"], 66: ["how-to", "pbs"], 67: ["pbs-kids"],
    68: ["movie", "action-movie"], 69: ["blockbuster-movie"], 70: ["drive-in"], 71: ["must-see-sitcom"], 72: ["commercials-90s"],
    73: ["blockbuster-movie", "disney-movie"], 74: ["star-trek"], 75: ["star-trek"], 76: ["star-trek"], 77: ["star-trek", "sci-fi"],
}


def channel(n, name, cat, color, tags, parts, queries):
    s = slug(name)
    themed = n >= 48 and n != 99
    for p in parts:
        assert all(x in range(1, 8) for x in p["days"])
        assert 360 <= p["startMinute"] < p["endMinute"] <= 1800, (name, p)
    # per-day overlap check
    for day in range(1, 8):
        spans = sorted((p["startMinute"], p["endMinute"], p["id"]) for p in parts if day in p["days"])
        for a, b in zip(spans, spans[1:]):
            assert b[0] >= a[1], (name, day, a, b)
    return {
        "id": f"ch-{n:02d}-{s}"[:64],
        "number": n,
        "displayName": name,
        "genre": cat.title(),
        "categoryLabel": cat,
        "color": color,
        "enabled": True,
        "scheduleRevision": "2",
        "epochUtcMs": 1767225600000,
        "policy": "DAYPARTED",
        "playlist": [],
        "fallbackPool": [],
        "timeZone": "America/New_York",
        "dayparts": parts,
        "defaultTags": tags,
        "fillerTags": [f"{s}-bumpers"[:40]] + filler_for(n),
        "discoveryQueries": (queries if themed else [f"{name.lower()} 199*"] + queries),
        "ignoreEra": themed,
        "fallbackTags": FALLBACK.get(n, []),
    }


manifest = {
    "schemaVersion": 1,
    "revision": REVISION,
    "generatedAtUtcMs": 1762182000000,
    "eraPolicy": {"minYear": 1990, "maxYear": 2015, "preferredMaxYear": 2005},
    "reviewPolicy": "AUTOMATIC",
    "channels": [channel(*c) for c in C],
    "assets": [],
}
nums = [c["number"] for c in manifest["channels"]]
assert len(nums) == len(set(nums))
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
text = json.dumps(manifest, indent=2)
for path in ("manifest/channel-surf-manifest.json", "app/src/main/assets/default_manifest.json"):
    with open(os.path.join(root, path), "w") as f:
        f.write(text)
print(f"{len(manifest['channels'])} channels written")
