#!/usr/bin/env python3
"""A fake Emby server (and fake Zidoo 9529 API) for checking zmby's UI without real servers.

Usage:
    python3 -I tools/mock_emby.py [--port 8096] [--zidoo-port 9529] [--video FILE]

From the Android emulator the host is http://10.0.2.2:8096 (any user name / password logs in).
For the Zidoo status API run `adb reverse tcp:9529 tcp:9529` so the app's 127.0.0.1:9529 reaches
this process. Playback reports are printed to stdout and update the in-memory resume points.

With --video, every /Videos/{id}/stream* and /videos/{id}/original.* request serves that file
(HTTP Range supported), so a real Zidoo box on the LAN can play it: log in with
http://<this mac's LAN IP>:8096 and pick 「测试片」, whose runtime and tracks match the 10-minute
clip (two audio tracks, two embedded subtitles, one external .srt). See README.md for local demo setup.

PlaybackInfo answers with an Emby-style relative DirectStreamUrl (turn off with
--no-direct-stream-url). --range-only-under-emby mimics a reverse proxy that honours Range only
under /emby (the root answers 200 with the whole file).

--multi also starts four servers that disagree with this one (ports --port+2 … +5): 朋友的服 (other
translated titles, upper-case ProviderIds keys, a pinyin plugin, only episodes 1–8 of one show, a
230-episode anime), 公益服 A (ignores AnyProviderIdEquals and returns its whole library, refuses
favourite writes with 403, 1.2 s per request, lower-case keys, one film twice with the same TMDB),
公益服 B (logs in, then hangs 25 s on every library request) and 无 ID 服 (no ProviderIds; one title
that matches on title + year, one with the wrong year) and, on --port+6, 严格服 (the main catalogue,
but only for official Emby clients: anything else, zmby and the Zidoo player's okhttp included, gets
an nginx-style 403 page). Log in to each as its own server. --official-only makes the main server strict.

--redirect mimics a 302 cloud-drive server (emby2alist and the like): stream URLs answer 302 to a
signed link on --signed-port (default 8097) with Cache-Control: max-age=--sign-ttl (default 60);
the link answers 403 once expired, and with --require-ua only to that User-Agent.
"""
import os
import argparse
import hashlib
import hmac
import json
import struct
import threading
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, parse_qs, urlparse

TICKS = 10_000  # per millisecond
USER = "u1"
TOKEN = "mock-token"

# ---------------------------------------------------------------- media streams


def video(w, h, codec="hevc", ext=None, sub=None, desc=None, bitrate=58_100_000, fps=23.976, primaries="bt2020", transfer="smpte2084"):
    return {"Index": 0, "Type": "Video", "Codec": codec, "Profile": "Main 10", "Width": w, "Height": h,
            "BitRate": bitrate, "BitDepth": 10, "RealFrameRate": fps, "VideoRange": "HDR" if ext else "SDR",
            "ExtendedVideoType": ext, "ExtendedVideoSubType": sub, "ExtendedVideoSubTypeDescription": desc,
            "ColorPrimaries": primaries, "ColorTransfer": transfer}


def audio(i, lang, codec, profile=None, ch=6, title=None, default=False, sr=48000, bd=None, br=None):
    return {"Index": i, "Type": "Audio", "Codec": codec, "Profile": profile, "Language": lang, "Channels": ch,
            "SampleRate": sr, "BitDepth": bd, "BitRate": br, "Title": title, "IsDefault": default}


def sub(i, lang, codec, title=None, default=False, external=False, forced=False):
    return {"Index": i, "Type": "Subtitle", "Codec": codec, "Language": lang, "Title": title,
            "IsDefault": default, "IsExternal": external, "IsForced": forced}


DUNE_STREAMS = [
    video(3840, 2160, ext="DolbyVision", sub="DoviProfile76", desc="Dolby Vision Profile 7.6 (FEL) + HDR10"),
    audio(1, "eng", "truehd", "TrueHD + Atmos", 8, default=True, bd=24),
    audio(2, "eng", "ac3", None, 6, br=640_000),
    audio(3, "chi", "dts", "DTS", 6, br=1_509_000),
    audio(4, "eng", "ac3", None, 2, title="导演评论"),
    audio(5, "jpn", "eac3", None, 6, br=768_000),
    sub(6, "chi", "PGSSUB", "简体中文", default=True),
    sub(7, "chi", "PGSSUB", "繁體中文"),
    sub(8, "chi", "ass", "简英双语", external=True),
    sub(9, "eng", "PGSSUB"),
    sub(10, "eng", "subrip", "English SDH", external=True),
    sub(11, "jpn", "PGSSUB"),
    sub(12, "kor", "PGSSUB"),
    sub(13, "eng", "PGSSUB", forced=True),
]


def source(sid, path, size_gb, bitrate, streams, container="mkv"):
    return {"Id": sid, "Name": path.rsplit("/", 1)[-1], "Path": path, "Protocol": "File", "Container": container,
            "Size": int(size_gb * 1024 ** 3), "Bitrate": bitrate, "RunTimeTicks": None,
            "SupportsDirectPlay": True, "SupportsDirectStream": True, "MediaStreams": streams}


def item(iid, name, typ, year=None, runtime_min=None, overview="", genres=(), color=(40, 50, 60), **extra):
    d = {"Id": iid, "Name": name, "Type": typ, "ProductionYear": year, "Overview": overview, "Genres": list(genres),
         "RunTimeTicks": runtime_min * 60_000 * TICKS if runtime_min else None, "CommunityRating": 8.1,
         "ProviderIds": {}, "ImageTags": {"Primary": "p"}, "BackdropImageTags": ["b"],
         "UserData": {"PlaybackPositionTicks": 0, "Played": False, "IsFavorite": False, "PlayedPercentage": None},
         "MediaSources": [], "Chapters": [{"Name": f"Chapter {i}", "StartPositionTicks": i * 300_000 * TICKS} for i in range(28)] if typ == "Movie" else []}
    d.update(extra)
    COLORS[iid] = color
    return d


COLORS = {}
ITEMS = {}
VIDEO = None
OPTS = {"direct_stream_url": True, "range_only_under_emby": False, "pinyin": False,
        "redirect": False, "signed_port": 8097, "sign_ttl": 60, "require_ua": None, "throttle_kbps": 0,
        "logo_delay": 0.0, "broken_logos": False, "dark_logo": False}
SIGN_KEY = os.urandom(16)


def signature(path, expires):
    return hmac.new(SIGN_KEY, f"{path}:{expires}".encode(), hashlib.sha256).hexdigest()[:20]


def add(d):
    ITEMS[d["Id"]] = d
    return d


add(item("testclip", "测试片（10 分钟）", "Movie", 2026, 10, "ffmpeg testsrc 生成的 1080p 测试视频，用于真机验证播放、进度回传与音轨字幕映射。", ["测试"], (60, 70, 90),
         MediaSources=[source("testclip-1080", "/test/test.1080p.mkv", 0.1, 3_100_000,
                              [video(1920, 1080, codec="h264", primaries="bt709", transfer="bt709", bitrate=3_000_000, fps=24),
                               audio(1, "eng", "aac", None, 1, title="English 440Hz", default=True),
                               audio(2, "chi", "aac", None, 1, title="中文 880Hz"),
                               sub(3, "chi", "subrip", "内嵌中文"),
                               sub(4, "eng", "subrip", "Embedded English"),
                               sub(5, "chi", "subrip", "外挂中文", external=True)])]))

add(item("dune2", "沙丘：第二部", "Movie", 2024, 166, "保罗·厄崔迪与契妮和弗雷曼人联手，踏上向摧毁他家族的阴谋者复仇之路。", ["科幻", "冒险"], (90, 74, 53),
         MediaSources=[
             source("dune2-remux", "/movies/沙丘2 (2024)/Dune.Part.Two.2024.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.TrueHD.Atmos.7.1.mkv", 58.2, 68_400_000, DUNE_STREAMS),
             source("dune2-web", "/movies/沙丘2 (2024)/Dune.Part.Two.2024.2160p.WEB-DL.HDR10+.DDP5.1.Atmos.mkv", 24.1, 26_000_000,
                    [video(3840, 2160, ext="Hdr10Plus", bitrate=24_000_000), audio(1, "eng", "eac3", "Dolby Digital Plus + Dolby Atmos", 6, default=True), sub(2, "chi", "subrip", "简体中文")]),
             source("dune2-1080", "/movies/沙丘2 (2024)/Dune.Part.Two.2024.1080p.BluRay.x264.DTS-HD.MA.mkv", 14.6, 16_000_000,
                    [video(1920, 1080, codec="h264", bitrate=14_000_000, primaries="bt709", transfer="bt709"), audio(1, "eng", "dts", "DTS-HD MA", 8, default=True)]),
             source("dune2-iso", "/movies/沙丘2 (2024)/Dune.Part.Two.2024.2160p.UHD.BluRay.iso", 82.1, None, [], container="iso"),
         ]))
ITEMS["dune2"]["UserData"].update({"PlaybackPositionTicks": 4_328_000 * TICKS, "PlayedPercentage": 43, "LastPlayedDate": "2026-10-22T20:00:00Z"})

add(item("oppen", "奥本海默", "Movie", 2023, 180, "1942 年，物理学家奥本海默受命领导曼哈顿计划。", ["剧情", "历史"], (82, 58, 51),
         MediaSources=[source("oppen-1", "/movies/Oppenheimer.2023.2160p.UHD.BluRay.REMUX.HDR.mkv", 71.0, 70_000_000,
                              [video(3840, 2160, ext="Hdr10"), audio(1, "eng", "dts", "DTS-HD MA", 6, default=True), sub(2, "chi", "PGSSUB", "简体中文", default=True)])]))
ITEMS["oppen"]["UserData"].update({"PlaybackPositionTicks": 1_620_000 * TICKS, "PlayedPercentage": 15, "LastPlayedDate": "2026-10-20T20:00:00Z"})

for n, (name, year, color) in enumerate([("星际穿越", 2014, (46, 58, 74)), ("银翼杀手 2049", 2017, (63, 53, 80)), ("降临", 2016, (47, 66, 68)),
                                          ("星际特工：千星之城", 2017, (68, 57, 45)), ("火星救援", 2015, (58, 63, 51)), ("瞬息全宇宙", 2022, (64, 45, 52))]):
    add(item(f"m{n}", name, "Movie", year, 140, f"{name}的简介。", ["科幻"], color,
             MediaSources=[source(f"m{n}-s", f"/movies/{name}.{year}.1080p.BluRay.mkv", 12.0, 12_000_000,
                                  [video(1920, 1080, codec="h264", primaries="bt709", transfer="bt709"), audio(1, "eng", "ac3", None, 6, default=True)])]))

# Real TMDB / IMDb ids, one per work (the multi-server mode merges on them).
for _iid, _ids in {"testclip": {"Tmdb": "990001"}, "dune2": {"Tmdb": "693134", "Imdb": "tt15239678"}, "oppen": {"Tmdb": "872585"},
                   "m0": {"Tmdb": "157336", "Imdb": "tt0816692"}, "m1": {"Tmdb": "335984", "Imdb": "tt1856101"}, "m2": {"Tmdb": "329865"},
                   "m3": {"Tmdb": "339964"}, "m4": {"Tmdb": "286217"}, "m5": {"Tmdb": "545611"}}.items():
    ITEMS[_iid]["ProviderIds"] = _ids

# Title logos (ImageTags.Logo) on some films; the series keep the text title, as in Series.dc.html.
for _iid in ("dune2", "oppen", "m0"):
    ITEMS[_iid]["ImageTags"]["Logo"] = "l"

for d in list(ITEMS.values()):
    d["_lib"] = "lib-movies"

# A film the server has not finished scraping: no art, runtime, overview or stream info.
add(item("raw1", "Some.Film.2025.1080p.WEB-DL", "Movie", None, None, "", (), (40, 40, 40),
         ImageTags={}, BackdropImageTags=[], ProviderIds={}, CommunityRating=None, Chapters=[], _lib="lib-movies",
         MediaSources=[dict(source("raw1-s", "/data/incoming/Some.Film.2025.1080p.WEB-DL.mkv", 3.2, None, []), Bitrate=None)]))

# Enough documentaries to need several pages in the browse grid.
for n in range(1, 131):
    add(item(f"doc{n}", f"纪录片 {n:03d}", "Movie", 2000 + n % 25, 50, "", ["纪录"], (30 + n % 40, 50, 60), _lib="lib-docs", ProviderIds={"Tmdb": str(800000 + n)},
             MediaSources=[source(f"doc{n}-s", f"/docs/Doc.{n:03d}.1080p.mkv", 2.0, 5_000_000,
                                  [video(1920, 1080, codec="h264", primaries="bt709", transfer="bt709"), audio(1, "chi", "aac", None, 2, default=True)])]))

add(item("season1", "第 1 季", "Season", 2023, IndexNumber=1, SeriesId="longseason"))
add(item("longseason", "漫长的季节", "Series", 2023, None, "东北小城的出租车司机王响，在一桩碎尸案中寻找儿子死亡的真相。", ["悬疑", "剧情"], (59, 74, 63), ChildCount=1, _lib="lib-tv",
         ProviderIds={"Tmdb": "202750"}))
EPISODES = []
for e in range(1, 13):
    ep = add(item(f"ep{e}", f"第 {e} 集的标题", "Episode", 2023, 49, f"第 {e} 集简介，取自 Emby Overview 字段。", (), (47 + e * 3, 61, 68),
                  SeriesId="longseason", SeriesName="漫长的季节", SeasonId="season1", ParentIndexNumber=1, IndexNumber=e,
                  SeriesPrimaryImageTag="p", ParentBackdropItemId="longseason", ParentBackdropImageTags=["b"],
                  MediaSources=[source(f"ep{e}-4k", f"/tv/漫长的季节/S01E{e:02d}.2160p.WEB-DL.DV.mkv", 6.1, 18_000_000,
                                       [video(3840, 2160, ext="DolbyVision", sub="DoviProfile81"), audio(1, "chi", "eac3", None, 6, default=True), sub(2, "chi", "subrip", "简体中文", default=True)])]
                  + ([source(f"ep{e}-1080", f"/tv/漫长的季节/S01E{e:02d}.1080p.WEB-DL.mkv", 2.0, 6_000_000,
                             [video(1920, 1080, codec="h264", primaries="bt709", transfer="bt709"), audio(1, "chi", "aac", None, 2, default=True)])] if e <= 8 else [])))
    if e < 7:
        ep["UserData"].update({"Played": True, "PlayedPercentage": 100})
    if e == 7:
        ep["UserData"].update({"PlaybackPositionTicks": 1_850_000 * TICKS, "PlayedPercentage": 63, "LastPlayedDate": "2026-10-23T01:00:00Z"})
    EPISODES.append(ep)

# A long-running show: one season of 230 episodes, so the season has to be fetched page by page.
add(item("anime", "长篇番剧", "Series", 2010, None, "一部集数很多的番剧。", ["动画"], (70, 50, 80), ChildCount=1, _lib="lib-tv", ProviderIds={"Tvdb": "300001"}))
add(item("anime-s1", "第 1 季", "Season", 2010, IndexNumber=1, SeriesId="anime"))
for e in range(1, 231):
    ep = add(item(f"an{e}", f"第 {e} 话", "Episode", 2010, 24, "", (), (60, 50, 70 + e % 30),
                  SeriesId="anime", SeriesName="长篇番剧", SeasonId="anime-s1", ParentIndexNumber=1, IndexNumber=e,
                  SeriesPrimaryImageTag="p", ParentBackdropItemId="anime", ParentBackdropImageTags=["b"],
                  MediaSources=[source(f"an{e}-s", f"/anime/S01E{e:03d}.1080p.mkv", 0.5, 3_000_000,
                                       [video(1920, 1080, codec="h264", primaries="bt709", transfer="bt709"), audio(1, "jpn", "aac", None, 2, default=True)])]))
    if e <= 204:
        ep["UserData"].update({"Played": True, "PlayedPercentage": 100})


def series_episodes(cat, series_id):
    return sorted((d for d in cat.items.values() if d["Type"] == "Episode" and d.get("SeriesId") == series_id),
                  key=lambda d: (d.get("ParentIndexNumber") or 0, d.get("IndexNumber") or 0))


def page_of(items, q):
    start = int(q.get("StartIndex") or 0)
    limit = int(q.get("Limit") or len(items) or 1)
    return {"Items": items[start:start + limit], "TotalRecordCount": len(items)}

# With --pinyin the mock behaves like a server with a pinyin search plugin (神医助手 / ViewMate):
# SearchTerm also matches these full-pinyin and initials spellings. Without it, like stock Emby,
# only the original title matches.
PINYIN = {
    "testclip": ["ceshipian", "csp"], "dune2": ["shaqiudierbu", "sqdeb"], "oppen": ["aobenhaimo", "abhm"],
    "m0": ["xingjichuanyue", "xjcy"], "m1": ["yinyishashou2049", "yyss2049"], "m2": ["jianglin", "jl"],
    "m3": ["xingjitegongqianxingzhicheng", "xjtgqxzc"], "m4": ["huoxingjiuyuan", "hxjy"], "m5": ["shunxiquanyuzhou", "sxqyz"],
    "longseason": ["manchangdejijie", "mcdjj"], "anime": ["changpianfanju", "cpfj"],
}
SEARCHES = {}  # term -> count, for asserting how many candidate requests went out (GET /_mock/stats)

VIEWS = [{"Id": "lib-movies", "Name": "电影", "Type": "CollectionFolder", "CollectionType": "movies"},
         {"Id": "lib-tv", "Name": "剧集", "Type": "CollectionFolder", "CollectionType": "tvshows"},
         {"Id": "lib-docs", "Name": "纪录片", "Type": "CollectionFolder", "CollectionType": "movies"}]

LIST_FIELDS_DROP = ("MediaSources", "Chapters", "People")


def brief(d, keep_sources=False, people=False):
    return {k: v for k, v in d.items() if not k.startswith("_") and (keep_sources or k not in LIST_FIELDS_DROP or (people and k == "People"))}


def request_kind(path, q):
    """What a request is for, as counted per server."""
    if path.endswith("/Items/Latest"):
        return "Latest"
    if path.endswith("/Items/Resume"):
        return "Resume"
    if path == "/Shows/NextUp":
        return "NextUp"
    if path.endswith("/Views"):
        return "Views"
    if path.endswith("/PlaybackInfo"):
        return "PlaybackInfo"
    if "/Images/" in path or path.endswith("/Images"):
        return "Images"
    if path == "/Persons" or path.startswith("/Persons/"):
        return "Persons"
    if path == "/Genres":
        return "Genres"
    if path.endswith("/Seasons"):
        return "Seasons"
    if path.endswith("/Episodes"):
        return "Episodes"
    if path.startswith("/Sessions/"):
        return "Report"
    if path.endswith("/Items") and "/Users/" in path:
        if q.get("SearchTerm"):
            return "Search"
        if q.get("AnyProviderIdEquals"):
            return "Ids"
        if q.get("PersonIds"):
            return "PersonItems"
        if q.get("Filters", "").find("IsFavorite") >= 0 and not q.get("SortBy", "").startswith("PremiereDate"):
            return "Favorites"
        return "Browse"
    if "/Items/" in path and path.startswith("/Users/"):
        return "Item"
    return "Other"


def sort_items(res, q):
    """SortBy (first field) and SortOrder as Emby applies them, enough for the browse page."""
    field = (q.get("SortBy") or "").split(",")[0]
    keyf = {
        "PremiereDate": lambda d: d.get("PremiereDate") or (f"{d['ProductionYear']:04d}" if d.get("ProductionYear") else ""),
        "ProductionYear": lambda d: d.get("ProductionYear") or 0,
        "DateCreated": lambda d: d.get("DateCreated", ""),
        "SortName": lambda d: (d.get("SortName") or d.get("Name") or "").lower(),
        "CommunityRating": lambda d: d.get("CommunityRating") or 0,
        "DatePlayed": lambda d: d["UserData"].get("LastPlayedDate", ""),
        "PlayCount": lambda d: 1 if d["UserData"].get("Played") else 0,
    }.get(field)
    if keyf:
        res = sorted(res, key=keyf, reverse=q.get("SortOrder") == "Descending")
    return res


def filter_items(res, q):
    """Filters, Genres (pipe-separated), Years, Is4K and NameLessThan."""
    for f in (q.get("Filters") or "").split(","):
        if f == "IsFavorite":
            res = [d for d in res if d["UserData"].get("IsFavorite")]
        elif f == "IsPlayed":
            res = [d for d in res if d["UserData"].get("Played")]
        elif f == "IsUnplayed":
            res = [d for d in res if not d["UserData"].get("Played")]
    if q.get("Genres"):
        wanted = set(q["Genres"].split("|"))
        res = [d for d in res if wanted & set(d.get("Genres") or [])]
    if q.get("Years"):
        years = {int(y) for y in q["Years"].split(",") if y}
        res = [d for d in res if d.get("ProductionYear") in years]
    if q.get("Is4K") == "true":
        res = [d for d in res if any((m.get("Width") or 0) >= 3200 for s in d.get("MediaSources", []) for m in s.get("MediaStreams", []))]
    if q.get("NameLessThan"):
        bound = q["NameLessThan"].lower()
        res = [d for d in res if (d.get("SortName") or d.get("Name") or "").lower() < bound]
    if q.get("PersonIds"):
        pid = q["PersonIds"]
        res = [d for d in res if any(p.get("Id") == pid for p in d.get("People", []))]
    return res


def movies():
    return [d for d in ITEMS.values() if d["Type"] == "Movie"]


CATALOGS = []


class Catalog:
    """One fake server: its items and how it misbehaves (multi-server mode)."""

    def __init__(self, name, sid, items, views, pinyin=False, ignore_ids=False, refuse_favorites=False, delay=0.0, hang=0, official_only=False, persons=None, image_scale=1):
        self.name, self.sid, self.items, self.views = name, sid, items, views
        self.persons = persons or {}
        # Image sizes /Items/{id}/Images reports (the app keeps the sharpest copy across servers).
        self.image_scale = image_scale
        # Requests by kind, for checking that a server outside a data source is not asked (GET /_mock/stats).
        self.requests = {}
        CATALOGS.append(self)
        self.official_only = official_only
        self.pinyin, self.ignore_ids, self.refuse_favorites, self.delay, self.hang = pinyin, ignore_ids, refuse_favorites, delay, hang
        self.searches = SEARCHES if items is ITEMS else {}


# Official Emby clients, as a strict public server's whitelist would list them. The official
# Android TV app sends Android's own agent "Dalvik/2.1.0 (…)" and Client="AndroidTv" (see
# app/src/main/java/app/zmby/emby/ClientIdentity.kt for the sources).
OFFICIAL_CLIENTS = {"AndroidTv", "Emby for Android", "Emby Theater", "Emby Web", "Emby for iOS"}


def official_refusal(headers, q):
    """Why a request is not from an official client (None when it is): User-Agent first, then the client name."""
    ua = headers.get("User-Agent") or ""
    if not (ua.startswith("Dalvik/") or ua.startswith("Mozilla/") or "Emby" in ua):
        return f"User-Agent {ua!r}"
    auth = headers.get("X-Emby-Authorization") or headers.get("Authorization") or ""
    client = headers.get("X-Emby-Client") or q.get("X-Emby-Client")
    if 'Client="' in auth:
        client = auth.split('Client="', 1)[1].split('"', 1)[0]
    if client is not None and client not in OFFICIAL_CLIENTS:
        return f"client {client!r}"
    return None


def stamp_dates(items):
    """DateCreated, newest last added, so 最新添加 can be merged across servers by date."""
    for i, d in enumerate(items.values()):
        d.setdefault("DateCreated", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(1_790_000_000 + i * 3600)))


stamp_dates(ITEMS)

# People (cast and crew). Ids are each server's own; TMDB person ids are real, so the app can
# merge the same person across servers (local-data spec). Names are Chinese on this server.
PERSONS = {
    "p-tc": {"Id": "p-tc", "Name": "提莫西·查拉梅", "ProviderIds": {"Tmdb": "1190668"}, "PremiereDate": "1995-12-27T00:00:00Z", "ProductionLocations": ["美国纽约"],
             "Overview": "美国演员，凭借《请以你的名字呼唤我》获得奥斯卡最佳男主角提名。" * 4},
    "p-zd": {"Id": "p-zd", "Name": "赞达亚", "ProviderIds": {"Tmdb": "505710"}, "PremiereDate": "1996-09-01T00:00:00Z", "Overview": "美国演员、歌手。"},
    "p-dv": {"Id": "p-dv", "Name": "丹尼斯·维伦纽瓦", "ProviderIds": {"Tmdb": "137427"}, "Overview": "加拿大导演。"},
    "p-cn": {"Id": "p-cn", "Name": "克里斯托弗·诺兰", "ProviderIds": {"Tmdb": "525"}, "PremiereDate": "1970-07-30T00:00:00Z", "ProductionLocations": ["英国伦敦"], "Overview": "英国导演、编剧。"},
    "p-cm": {"Id": "p-cm", "Name": "基里安·墨菲", "ProviderIds": {"Tmdb": "2037"}, "Overview": ""},
    "p-mm": {"Id": "p-mm", "Name": "马修·麦康纳", "ProviderIds": {"Tmdb": "10297"}},
    "p-ah": {"Id": "p-ah", "Name": "安妮·海瑟薇", "ProviderIds": {"Tmdb": "1813"}},
    "p-noimg": {"Id": "p-noimg", "Name": "无头像演员", "ProviderIds": {}},
}
for _pid, _p in PERSONS.items():
    _p.setdefault("Type", "Person")
    _p["ImageTags"] = {} if _pid == "p-noimg" else {"Primary": "pp"}
    COLORS[_pid] = (90, 80, 70)


def people(*rows):
    """People entries: (person id, type, role)."""
    return [{"Id": pid, "Name": PERSONS[pid]["Name"], "Type": typ, "Role": role, **({"PrimaryImageTag": "pp"} if PERSONS[pid]["ImageTags"] else {})} for pid, typ, role in rows]


ITEMS["dune2"]["People"] = people(("p-tc", "Actor", "保罗·厄崔迪"), ("p-zd", "Actor", "契妮"), ("p-noimg", "Actor", "弗雷曼人"), ("p-dv", "Director", None), ("p-dv", "Writer", None))
ITEMS["oppen"]["People"] = people(("p-cm", "Actor", "J·罗伯特·奥本海默"), ("p-cn", "Director", None), ("p-cn", "Writer", None))
ITEMS["m0"]["People"] = people(("p-mm", "Actor", "库珀"), ("p-ah", "Actor", "布兰德"), ("p-tc", "Actor", "汤姆"), ("p-cn", "Director", None), ("p-cn", "Writer", None), ("p-cn", "Producer", None))
ITEMS["longseason"]["People"] = people(("p-noimg", "Actor", "王响"))
MAIN = Catalog("家里 NAS", "mock-nas", ITEMS, VIEWS, persons=PERSONS)

TWO_LIBS = [{"Id": "lib-movies", "Name": "电影", "Type": "CollectionFolder", "CollectionType": "movies"},
            {"Id": "lib-tv", "Name": "剧集", "Type": "CollectionFolder", "CollectionType": "tvshows"}]


def copy_of(src_id, new_id, name=None, ids=None, sources=None, **extra):
    """Another server's copy of a work: own id, maybe another translated title and id key spelling."""
    import copy
    d = copy.deepcopy(ITEMS[src_id])
    d["Id"] = new_id
    COLORS[new_id] = COLORS.get(src_id, (50, 60, 70))
    if name:
        d["Name"] = name
    if ids is not None:
        d["ProviderIds"] = ids
    if sources is not None:
        d["MediaSources"] = sources
    d["UserData"] = {"PlaybackPositionTicks": 0, "Played": False, "IsFavorite": False, "PlayedPercentage": None}
    d.update(extra)
    return d


def h264(w, h):
    return [video(w, h, codec="h264", primaries="bt709", transfer="bt709", bitrate=8_000_000), audio(1, "chi", "aac", None, 2, default=True)]


def series_copy(src, new_id, name, ids, episodes, src_prefix, new_prefix, quality=(1920, 1080)):
    """A series on another server with its season and the given episode numbers."""
    out = {new_id: copy_of(src, new_id, name, ids)}
    season = new_id + "-s1"
    out[season] = item(season, "第 1 季", "Season", 2023, IndexNumber=1, SeriesId=new_id)
    for e in episodes:
        ep = copy_of(f"{src_prefix}{e}", f"{new_prefix}{e}", ids={}, SeriesId=new_id, SeriesName=name, SeasonId=season, ParentBackdropItemId=new_id,
                     sources=[source(f"{new_prefix}{e}-s", f"/tv/{name}/S01E{e:03d}.{quality[1]}p.mkv", 1.5, 6_000_000, h264(*quality))])
        out[ep["Id"]] = ep
    return out


def build_friend():
    """朋友的服: other translations, upper-case ID keys, pinyin plugin, fewer episodes of one show."""
    items = {}
    for d in [
        copy_of("dune2", "f-dune", "沙丘2", {"TMDB": "693134"}, [source("f-dune-web", "/movies/Dune2/Dune.Part.Two.2024.2160p.WEB-DL.HDR10.mkv", 21.0, 24_000_000,
                                                                       [video(3840, 2160, ext="Hdr10", bitrate=24_000_000), audio(1, "eng", "eac3", "Dolby Digital Plus + Dolby Atmos", 6, default=True)]),
                                                                source("f-dune-1080", "/movies/Dune2/Dune.Part.Two.2024.1080p.mkv", 9.0, 9_000_000, h264(1920, 1080))]),
        copy_of("m0", "f-inter", "星际效应", {"TMDB": "157336"}, [source("f-inter-s", "/movies/Interstellar.2014.2160p.mkv", 40.0, 40_000_000, [video(3840, 2160, ext="DolbyVision", sub="DoviProfile81"), audio(1, "eng", "truehd", "TrueHD + Atmos", 8, default=True)])]),
        copy_of("m1", "f-blade", "银翼杀手 2049", {"IMDB": "tt1856101"}, [source("f-blade-s", "/movies/BR2049.1080p.mkv", 12.0, 12_000_000, h264(1920, 1080))]),
        copy_of("testclip", "f-test", "测试片（朋友的服）", {"TMDB": "990001"}),
    ]:
        d["_lib"] = "lib-movies"
        items[d["Id"]] = d
    items["f-dune"]["UserData"].update({"PlaybackPositionTicks": 3_100_000 * TICKS, "PlayedPercentage": 31, "LastPlayedDate": "2026-10-23T08:00:00Z"})
    for k, v in series_copy("longseason", "f-ls", "The Long Season", {"TMDB": "202750"}, range(1, 9), "ep", "f-ls-e").items():
        items[k] = dict(v, _lib="lib-tv") if v["Type"] == "Series" else v
    for k, v in series_copy("anime", "f-an", "长篇番剧", {"TVDB": "300001"}, range(1, 231), "an", "f-an-e", quality=(3840, 2160)).items():
        items[k] = dict(v, _lib="lib-tv") if v["Type"] == "Series" else v
    # Its own person ids and English names; TMDB ids match 家里 NAS (one person in the app).
    for d in items.values():
        d["People"] = [dict(p, Id="f" + p["Id"], Name=FRIEND_NAMES.get(p["Id"], p["Name"])) for p in d.get("People", [])]
    stamp_dates(items)
    return items


FRIEND_NAMES = {"p-tc": "Timothée Chalamet", "p-zd": "Zendaya", "p-dv": "Denis Villeneuve", "p-cn": "Christopher Nolan", "p-cm": "Cillian Murphy",
                "p-mm": "Matthew McConaughey", "p-ah": "Anne Hathaway", "p-noimg": "No Picture"}


def friend_persons():
    import copy
    out = {}
    for pid, p in PERSONS.items():
        q = copy.deepcopy(p)
        q["Id"] = "f" + pid
        q["Name"] = FRIEND_NAMES[pid]
        q["Overview"] = "English biography from 朋友的服."
        COLORS[q["Id"]] = (70, 80, 90)
        out[q["Id"]] = q
    return out


def build_public_a():
    """公益服 A: ignores AnyProviderIdEquals, refuses favourites, slow; lower-case keys; one film twice."""
    items = {}
    for d in [
        copy_of("oppen", "a-oppen-4k", "奥本海默", {"tmdb": "872585"}),
        copy_of("oppen", "a-oppen-1080", "奥本海默", {"tmdb": "872585"}, [source("a-oppen-1080-s", "/1080/Oppenheimer.1080p.mkv", 10.0, 10_000_000, h264(1920, 1080))]),
        copy_of("dune2", "a-dune", "沙丘：第二部", {"tmdb": "693134"}, [source("a-dune-iso", "/iso/Dune.Part.Two.2024.2160p.UHD.BluRay.iso", 82.1, None, [], container="iso")]),
        copy_of("m2", "a-arrival", "降临", {"tmdb": "329865"}),
        copy_of("m3", "a-bebop", "星际牛仔 剧场版", {}, Overview="没有刮削到外部 ID 的条目。", ProductionYear=2001),
    ]:
        d["_lib"] = "lib-movies"
        items[d["Id"]] = d
    stamp_dates(items)
    return items


def build_public_b():
    """公益服 B: logs in, then never answers library requests in time."""
    items = {"b-dune": dict(copy_of("dune2", "b-dune", "沙丘：第二部"), _lib="lib-movies")}
    stamp_dates(items)
    return items


def build_noid():
    """无 ID 服: nothing scraped with IDs; title + year decides, a wrong year must not merge."""
    items = {}
    for d in [
        copy_of("m2", "n-arrival", "降临", {}, ProductionYear=2016),
        copy_of("dune2", "n-dune", "沙丘：第二部", {}, ProductionYear=2023, Overview="年份写错的条目，不能和 2024 年的合并。"),
        copy_of("m5", "n-lilo", "星际宝贝", {}, ProductionYear=2002),
    ]:
        d["_lib"] = "lib-movies"
        items[d["Id"]] = d
    stamp_dates(items)
    return items


# With --pinyin the main server also understands pinyin; 朋友的服 always does (it has the plugin).
PINYIN_EXTRA = {"f-dune": ["shaqiu2", "sq2"], "f-inter": ["xingjixiaoying", "xjxy"], "f-ls": [], "f-an": ["changpianfanju", "cpfj"]}

# ---------------------------------------------------------------- images


def png(w, h, rgb, seed):
    """A vertical gradient with a diagonal band, so scrims and crops are visible."""
    r0, g0, b0 = rgb
    rows = []
    for y in range(h):
        t = y / max(h - 1, 1)
        row = bytearray([0])
        for x in range(w):
            band = 40 if (x + y + seed * 37) % 160 < 18 else 0
            row += bytes((min(255, int(r0 * (1.4 - t)) + band), min(255, int(g0 * (1.4 - t)) + band), min(255, int(b0 * (1.4 - t)) + band)))
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b"")


def logo_png(w, h, seed, ink=255):
    """A transparent title logo: white (or [ink] grey) letter-like blocks on alpha, like a real clearlogo."""
    rows = []
    blocks = []
    x = 10
    n = 0
    while x < w - 40:
        bw = 28 + (seed * 13 + n * 17) % 34
        blocks.append((x, x + bw, 30 + (n * 11 + seed) % 40))
        x += bw + 14
        n += 1
    for y in range(h):
        row = bytearray([0])
        for xx in range(w):
            on = any(a <= xx < b and top <= y < h - 24 for a, b, top in blocks)
            row += bytes((ink, ink, ink, 255 if on else 0))
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b"")


IMAGE_CACHE = {}

# ---------------------------------------------------------------- zidoo status (fake player state)

ZIDOO = {"open": False, "position": 0, "duration": 0, "path": "", "playing": True, "since": 0.0}


def zidoo_status():
    if not ZIDOO["open"]:
        return {"status": 806, "msg": "The resource does not exist"}
    pos = ZIDOO["position"]
    if ZIDOO["playing"]:
        pos += int((time.time() - ZIDOO["since"]) * 1000)
    return {"status": 200, "video": {"status": 1 if ZIDOO["playing"] else 0, "title": ZIDOO["path"].rsplit("/", 1)[-1],
                                      "path": ZIDOO["path"], "currentPosition": pos, "duration": ZIDOO["duration"],
                                      "width": 3840, "height": 2160}, "audio": {"index": 0}, "subtitle": {"index": 1}}

# ---------------------------------------------------------------- handler


class Emby(BaseHTTPRequestHandler):
    server_version = "MockEmby/0.1"

    def log_message(self, fmt, *args):
        pass

    def send_json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        try:
            return json.loads(raw or b"{}")
        except ValueError:
            return {}

    def route(self, method):
        u = urlparse(self.path)
        p = u.path.rstrip("/")
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        parts = [x for x in p.split("/") if x]
        had_emby = bool(parts) and parts[0].lower() == "emby"
        if had_emby:
            parts = parts[1:]
        path = "/" + "/".join(parts)
        cat = self.server.cat
        print(f"[{cat.name}] {method} {self.path}", flush=True)

        # Fake Zidoo control API (served on the same port for convenience when --zidoo-port equals it).
        if path == "/ZidooVideoPlay/getPlayStatus":
            return self.send_json(zidoo_status())
        if path == "/ZidooVideoPlay/seekTo":
            ZIDOO.update(position=int(q.get("positon", 0)), since=time.time())
            return self.send_json({"status": 200})
        if path == "/_mock/zidoo":  # test hook: /_mock/zidoo?open=1&path=..&position=..&duration=..&playing=0|1
            ZIDOO.update(open=q.get("open", "1") == "1", path=q.get("path", ZIDOO["path"]),
                         position=int(q.get("position", ZIDOO["position"])), duration=int(q.get("duration", ZIDOO["duration"])),
                         playing=q.get("playing", "1") == "1", since=time.time())
            return self.send_json(zidoo_status())

        if path == "/_mock/stats":
            return self.send_json({"searches": cat.searches, "requests": cat.requests})
        if path == "/_mock/allstats":  # every server's request counts by kind, by server name
            return self.send_json({c.name: c.requests for c in CATALOGS})
        if path == "/_mock/reset":
            for c in CATALOGS:
                c.requests.clear()
            return self.send_json({"ok": True})
        if not path.startswith("/ZidooVideoPlay") and path != "/System/Info/Public":
            kind = request_kind(path, q)
            cat.requests[kind] = cat.requests.get(kind, 0) + 1
        if cat.official_only:
            refusal = official_refusal(self.headers, q)
            if refusal:
                # Like an nginx UA whitelist: a plain HTML 403 page, for API, images and streams alike.
                print(f"  [{cat.name}] 403 not an official client: {refusal}", flush=True)
                page = b"<html><head><title>403 Forbidden</title></head><body><center><h1>403 Forbidden</h1></center></body></html>"
                self.send_response(403)
                self.send_header("Content-Type", "text/html")
                self.send_header("Content-Length", str(len(page)))
                self.end_headers()
                if method != "HEAD":
                    self.wfile.write(page)
                return None
            print(f"  [{cat.name}] official client ok: {self.headers.get('User-Agent')}", flush=True)
        if path == "/System/Info/Public":
            return self.send_json({"Id": cat.sid, "ServerName": cat.name, "Version": "4.9.0.0"})
        if path == "/Users/AuthenticateByName" and method == "POST":
            return self.send_json({"User": {"Id": USER, "Name": self.body().get("Username", "me")}, "AccessToken": TOKEN, "ServerId": cat.sid})
        token = self.headers.get("X-Emby-Token") or q.get("api_key")
        if "/Images/" not in path and token != TOKEN:
            return self.send_json({"error": "unauthorized"}, 401)
        if path == f"/Users/{USER}" and method == "GET":
            return self.send_json({"Id": USER, "Name": "Mock User"})
        if path.startswith(f"/Users/{USER}/") or path.startswith("/Shows/"):
            if cat.hang:
                print(f"  [{cat.name}] hanging {cat.hang}s", flush=True)
                time.sleep(cat.hang)
            elif cat.delay:
                time.sleep(cat.delay)

        if len(parts) >= 4 and parts[0] == "Items" and parts[2] == "Images":
            iid, kind = parts[1], parts[3]
            key = (iid, kind)
            if kind == "Logo" and OPTS["logo_delay"]:
                time.sleep(OPTS["logo_delay"])
            if kind == "Logo" and OPTS["broken_logos"]:
                return self.send_json({"error": "no logo"}, 404)
            if key not in IMAGE_CACHE and kind == "Logo":
                # --dark-logo: one film's Logo in black ink, like some real clearlogos (title must still show).
                IMAGE_CACHE[key] = logo_png(520, 150, hash(iid) % 5, 0 if OPTS["dark_logo"] and iid == "m0" else 255)
            if key not in IMAGE_CACHE:
                w, h = {"Primary": (200, 300), "Backdrop": (480, 270), "Thumb": (400, 225)}.get(kind, (200, 300))
                if cat.items.get(iid, {}).get("Type") == "Episode" and kind == "Primary":
                    w, h = 400, 225
                IMAGE_CACHE[key] = png(w, h, COLORS.get(iid, (50, 60, 70)), hash(iid) % 7)
            data = IMAGE_CACHE[key]
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            return self.wfile.write(data)

        if len(parts) == 3 and parts[0] == "Items" and parts[2] == "Images":
            d = cat.items.get(parts[1]) or cat.persons.get(parts[1])
            if not d:
                return self.send_json([], 404)
            k = cat.image_scale
            out = []
            if d.get("ImageTags", {}).get("Primary"):
                out.append({"ImageType": "Primary", "ImageTag": d["ImageTags"]["Primary"], "Width": 1000 * k, "Height": 1500 * k, "Path": f"/meta/{parts[1]}/poster.jpg"})
            for i, t in enumerate(d.get("BackdropImageTags", [])):
                out.append({"ImageType": "Backdrop", "ImageIndex": i, "ImageTag": t, "Width": 1920 * k, "Height": 1080 * k, "Path": f"/meta/{parts[1]}/backdrop.jpg"})
            if d.get("ImageTags", {}).get("Logo"):
                out.append({"ImageType": "Logo", "ImageTag": d["ImageTags"]["Logo"], "Width": 800 * k, "Height": 310 * k, "Path": f"/meta/{parts[1]}/logo.png"})
            return self.send_json(out)
        if parts and parts[0] == "Persons" and len(parts) == 2:
            name = unquote(parts[1])
            person = next((p for p in cat.persons.values() if p["Name"] == name), None)
            print(f"  PERSON {name!r} -> {person and person['Id']}", flush=True)
            return self.send_json(person) if person else self.send_json({"error": "no such person"}, 404)
        if path == "/Persons":
            wanted = {tuple(x.lower().split(".", 1)) for x in q.get("AnyProviderIdEquals", "").split(",") if "." in x}
            res = [p for p in cat.persons.values() if any((k.lower(), str(v).lower()) in wanted for k, v in p.get("ProviderIds", {}).items())]
            return self.send_json(page_of(res, q))
        if path == "/Genres":
            res = [d for d in cat.items.values() if d["Type"] in (q.get("IncludeItemTypes") or "Movie,Series").split(",")]
            if q.get("ParentId"):
                res = [d for d in res if d.get("_lib") == q["ParentId"]]
            names = sorted({g for d in res for g in d.get("Genres") or []})
            return self.send_json({"Items": [{"Id": f"g-{n}", "Name": n, "Type": "Genre"} for n in names], "TotalRecordCount": len(names)})
        if path == f"/Users/{USER}/Views":
            return self.send_json({"Items": cat.views, "TotalRecordCount": len(cat.views)})
        if path == f"/Users/{USER}/Items/Resume":
            res = [d for d in cat.items.values() if d["UserData"].get("PlaybackPositionTicks")]
            res.sort(key=lambda d: d["UserData"].get("LastPlayedDate", ""), reverse=True)
            return self.send_json({"Items": [brief(d) for d in res], "TotalRecordCount": len(res)})
        if path == f"/Users/{USER}/Items/Latest":
            parent = q.get("ParentId")
            limit = int(q.get("Limit") or 24)
            lib = [d for d in cat.items.values() if (parent is None or d.get("_lib") == parent) and d["Type"] in ("Movie", "Series")]
            lib.sort(key=lambda d: d.get("DateCreated", ""), reverse=True)
            return self.send_json([brief(d) for d in lib][:limit])
        if path == f"/Users/{USER}/Items":
            types = (q.get("IncludeItemTypes") or "Movie,Series").split(",")
            pool = list(cat.items.values()) + (list(cat.persons.values()) if "Person" in types else [])
            res = [d for d in pool if d["Type"] in types]
            if q.get("Ids"):
                res = [d for d in res if d["Id"] in q["Ids"].split(",")]
            if q.get("ParentId"):
                res = [d for d in res if d.get("_lib") == q["ParentId"]]
            any_ids = q.get("AnyProviderIdEquals")
            if any_ids:
                cat.searches["ids:" + any_ids] = cat.searches.get("ids:" + any_ids, 0) + 1
                if cat.ignore_ids:
                    print(f"  [{cat.name}] ignores AnyProviderIdEquals={any_ids}: returns the whole library", flush=True)
                else:
                    wanted = {tuple(x.split(".", 1)) for x in any_ids.split(",") if "." in x}
                    res = [d for d in res if any((k.lower(), str(v)) in {(a.lower(), b) for a, b in wanted} for k, v in d.get("ProviderIds", {}).items())]
                    print(f"  IDS {any_ids} -> {len(res)} hits", flush=True)
            res = sort_items(filter_items(res, q), q)
            term = q.get("SearchTerm")
            if term:
                t = term.lower().replace(" ", "")
                cat.searches[term] = cat.searches.get(term, 0) + 1
                res = [d for d in res if t in d["Name"].lower().replace(" ", "")
                       or (cat.pinyin and any(a.startswith(t) for a in PINYIN.get(d["Id"], PINYIN_EXTRA.get(d["Id"], []))))]
                print(f"  SEARCH term={term!r} limit={q.get('Limit')} -> {len(res)} hits (pinyin={'on' if cat.pinyin else 'off'})", flush=True)
                time.sleep(0.6)  # make the "querying" state visible
            with_people = "People" in (q.get("Fields") or "")
            if q.get("Limit") == "0":
                return self.send_json({"Items": [], "TotalRecordCount": len(res)})
            return self.send_json(page_of([brief(d, people=with_people) for d in res], q))
        if len(parts) == 4 and parts[:3] == ["Users", USER, "Items"]:
            d = cat.items.get(parts[3]) or cat.persons.get(parts[3])
            return self.send_json(brief(d, keep_sources=True)) if d else self.send_json({}, 404)
        if path.startswith("/Shows/") and path.endswith("/Seasons"):
            sid = parts[1]
            seasons = [d for d in cat.items.values() if d["Type"] == "Season" and d.get("SeriesId") == sid]
            return self.send_json({"Items": [brief(s) for s in seasons], "TotalRecordCount": len(seasons)})
        if path.startswith("/Shows/") and path.endswith("/Episodes"):
            eps = series_episodes(cat, parts[1])
            if q.get("SeasonId"):
                eps = [e for e in eps if e.get("SeasonId") == q["SeasonId"]]
            if q.get("StartItemId"):
                ids = [e["Id"] for e in eps]
                eps = eps[ids.index(q["StartItemId"]):] if q["StartItemId"] in ids else eps
            return self.send_json(page_of([brief(e, keep_sources=True) for e in eps], q))
        if path == "/Shows/NextUp":
            out = []
            for sid in ([q["SeriesId"]] if q.get("SeriesId") else [d["Id"] for d in cat.items.values() if d["Type"] == "Series"]):
                eps = series_episodes(cat, sid)
                if any(e["UserData"]["Played"] for e in eps):
                    nxt = next((e for e in eps if not e["UserData"]["Played"]), None)
                    if nxt:
                        out.append(brief(nxt))
            return self.send_json({"Items": out, "TotalRecordCount": len(out)})
        if len(parts) == 4 and parts[0] == "Users" and parts[2] in ("PlayedItems", "FavoriteItems"):
            d = cat.items.get(parts[3])
            if not d:
                return self.send_json({}, 404)
            key = "Played" if parts[2] == "PlayedItems" else "IsFavorite"
            if key == "IsFavorite" and cat.refuse_favorites:
                print(f"  [{cat.name}] refuses favourite write ({method}) -> 403", flush=True)
                return self.send_json({"error": "not allowed"}, 403)
            print(f"  [{cat.name}] {key} {'set' if method == 'POST' else 'cleared'} on {parts[3]}", flush=True)
            d["UserData"][key] = method == "POST"
            return self.send_json(d["UserData"])
        if len(parts) == 3 and parts[0] == "Items" and parts[2] == "PlaybackInfo":
            d = cat.items.get(parts[1])
            sources = d["MediaSources"] if d else []
            want = q.get("MediaSourceId")
            if want:
                sources = [s for s in sources if s["Id"] == want] or sources
            ps = f"ps-{int(time.time())}"
            out = []
            for s in sources:
                s = dict(s)
                if OPTS["direct_stream_url"]:
                    s["DirectStreamUrl"] = (f"/videos/{parts[1]}/original.{s.get('Container') or 'mkv'}?DeviceId=mock"
                                            f"&MediaSourceId={s['Id']}&PlaySessionId={ps}&api_key={TOKEN}")
                s["MediaStreams"] = [dict(m, DeliveryUrl=f"/Videos/{parts[1]}/{s['Id']}/Subtitles/{m['Index']}/0/Stream.srt?api_key={TOKEN}")
                                     if m.get("IsExternal") else m for m in s.get("MediaStreams", [])]
                out.append(s)
            return self.send_json({"MediaSources": out, "PlaySessionId": ps})
        if "/subtitles/" in path.lower() and "/stream." in path.lower():
            return self.send_subtitle(path)
        low = path.lower()
        if VIDEO and low.startswith("/videos/") and ("/stream" in low or "/original" in low):
            if OPTS["redirect"]:
                return self.redirect_to_signed(parts[1])
            return self.stream_file(VIDEO, honour_range=had_emby or not OPTS["range_only_under_emby"])
        if path.startswith("/Sessions/Playing"):
            b = self.body()
            print(f"  [{cat.name}] REPORT {path} item={b.get('ItemId')} source={b.get('MediaSourceId')} pos={b.get('PositionTicks', 0) // TICKS}ms paused={b.get('IsPaused')}", flush=True)
            d = cat.items.get(b.get("ItemId"))
            if d and path.endswith("Stopped"):
                pos = b.get("PositionTicks", 0)
                run = d.get("RunTimeTicks") or 1
                if pos >= run * 0.9:
                    d["UserData"].update({"Played": True, "PlaybackPositionTicks": 0})
                else:
                    d["UserData"].update({"PlaybackPositionTicks": pos, "LastPlayedDate": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())})
            self.send_response(204)
            self.end_headers()
            return None
        return self.send_json({"error": f"not mocked: {path}"}, 404)

    def redirect_to_signed(self, item_id):
        host = (self.headers.get("Host") or "127.0.0.1").rsplit(":", 1)[0]
        expires = int(time.time()) + OPTS["sign_ttl"]
        path = f"/signed/{item_id}.mkv"
        location = f"http://{host}:{OPTS['signed_port']}{path}?expires={expires}&sign={signature(path, expires)}"
        print(f"  302 {self.path.split('?')[0]} -> {path} expires in {OPTS['sign_ttl']}s range={self.headers.get('Range')} ua={self.headers.get('User-Agent')}", flush=True)
        self.send_response(302)
        self.send_header("Location", location)
        self.send_header("Cache-Control", f"private, max-age={OPTS['sign_ttl']}")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def send_subtitle(self, path):
        """An .srt whose cues say which track this is, so the screen shows what the player picked."""
        cues = [f"{i + 1}\n00:{i // 12:02d}:{(i % 12) * 5:02d},000 --> 00:{i // 12:02d}:{(i % 12) * 5 + 4:02d},000\n外挂字幕 第 {i + 1} 句\n"
                for i in range(120)]
        body = "\n".join(cues).encode()
        print(f"  SUBTITLE {path}", flush=True)
        self.send_response(200)
        self.send_header("Content-Type", "application/x-subrip; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def stream_file(self, file_path, honour_range=True, log=True, kbps=0):
        size = os.path.getsize(file_path)
        start, end = 0, size - 1
        rng = self.headers.get("Range") if honour_range else None
        if log:
            print(f"  STREAM {self.path.split('?')[0]} range={self.headers.get('Range')}{'' if honour_range else ' (ignored)'} ua={self.headers.get('User-Agent')}", flush=True)
        if rng and rng.startswith("bytes="):
            a, _, b = rng[6:].split(",")[0].partition("-")
            if a:
                start = int(a)
                end = int(b) if b else size - 1
            else:
                start = max(0, size - int(b))
            end = min(end, size - 1)
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)
        self.send_header("Content-Type", "video/x-matroska")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        if self.command == "HEAD":
            return 0
        sent = 0
        with open(file_path, "rb") as f:
            f.seek(start)
            left = end - start + 1
            try:
                while left > 0:
                    chunk = f.read(min(64 << 10 if kbps else 1 << 20, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
                    sent += len(chunk)
                    if kbps:
                        time.sleep(len(chunk) / (kbps * 1024))
            except (BrokenPipeError, ConnectionResetError):
                pass
        return sent

    def do_HEAD(self):
        self.route("HEAD")

    def do_GET(self):
        self.route("GET")

    def do_POST(self):
        self.route("POST")

    def do_DELETE(self):
        self.route("DELETE")


class Signed(Emby):
    """The cloud drive behind --redirect: serves the video only with a valid, unexpired signature."""

    def route(self, method):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        expires = int(q.get("expires", "0") or 0)
        left = expires - int(time.time())
        ua = self.headers.get("User-Agent")
        if not VIDEO or not u.path.startswith("/signed/"):
            return self.send_json({"error": "not found"}, 404)
        if not hmac.compare_digest(q.get("sign", ""), signature(u.path, expires)):
            print(f"  SIGNED 403 bad signature range={self.headers.get('Range')}", flush=True)
            return self.send_json({"error": "bad signature"}, 403)
        if left < 0:
            print(f"  SIGNED 403 expired {-left}s ago range={self.headers.get('Range')} ua={ua}", flush=True)
            return self.send_json({"error": "expired"}, 403)
        if OPTS["require_ua"] and ua != OPTS["require_ua"]:
            print(f"  SIGNED 403 wrong UA {ua!r}", flush=True)
            return self.send_json({"error": "user agent"}, 403)
        print(f"  SIGNED ok ({left}s left) range={self.headers.get('Range')} ua={ua}", flush=True)
        started = time.time()
        sent = self.stream_file(VIDEO, log=False, kbps=OPTS["throttle_kbps"])
        print(f"  SIGNED done range={self.headers.get('Range')} {sent} bytes in {time.time() - started:.0f}s "
              f"(link {'expired' if time.time() > expires else 'valid'} at the end)", flush=True)
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8096)
    ap.add_argument("--zidoo-port", type=int, default=9529)
    ap.add_argument("--video", help="file served for every /Videos/{id}/stream request")
    ap.add_argument("--no-direct-stream-url", action="store_true", help="leave DirectStreamUrl out of PlaybackInfo")
    ap.add_argument("--range-only-under-emby", action="store_true", help="ignore Range unless the path starts with /emby")
    ap.add_argument("--pinyin", action="store_true", help="SearchTerm also matches pinyin (like a server with a pinyin plugin)")
    ap.add_argument("--redirect", action="store_true", help="302 stream URLs to short-lived signed links (cloud-drive server)")
    ap.add_argument("--signed-port", type=int, default=8097)
    ap.add_argument("--sign-ttl", type=int, default=60, help="seconds a signed link stays valid (its max-age)")
    ap.add_argument("--require-ua", help="signed links answer only this User-Agent")
    ap.add_argument("--throttle-kbps", type=int, default=0,
                    help="cap each signed-link response at this many KiB/s, so the player cannot buffer the whole clip before the link expires")
    ap.add_argument("--logo-delay", type=float, default=0, help="answer Logo images only after this many seconds (title text shows meanwhile)")
    ap.add_argument("--dark-logo", action="store_true", help="the first film's Logo is drawn in black on transparent (zmby lightens it)")
    ap.add_argument("--broken-logos", action="store_true", help="Logo images answer 404 although ImageTags.Logo is set")
    ap.add_argument("--official-only", action="store_true",
                    help="answer only official Emby clients (User-Agent and client name), 403 HTML page for anything else")
    ap.add_argument("--multi", action="store_true",
                    help="also run five more servers that disagree with this one, on --port+2 … --port+6 (see MULTI below)")
    a = ap.parse_args()
    global VIDEO
    VIDEO = a.video
    OPTS["direct_stream_url"] = not a.no_direct_stream_url
    OPTS["range_only_under_emby"] = a.range_only_under_emby
    OPTS["pinyin"] = a.pinyin
    OPTS.update(redirect=a.redirect, signed_port=a.signed_port, sign_ttl=a.sign_ttl, require_ua=a.require_ua, throttle_kbps=a.throttle_kbps)
    MAIN.pinyin = a.pinyin
    MAIN.official_only = a.official_only
    OPTS.update(logo_delay=a.logo_delay, broken_logos=a.broken_logos, dark_logo=a.dark_logo)

    def serve(host, port, handler, cat):
        srv = ThreadingHTTPServer((host, port), handler)
        srv.cat = cat
        return srv

    servers = [serve("0.0.0.0", a.port, Emby, MAIN)]
    if a.zidoo_port and a.zidoo_port != a.port:
        servers.append(serve("127.0.0.1", a.zidoo_port, Emby, MAIN))
    if a.redirect:
        servers.append(serve("0.0.0.0", a.signed_port, Signed, MAIN))
    if a.multi:
        # MULTI: servers that disagree with 家里 NAS in the ways real ones do.
        for offset, cat in ((2, Catalog("朋友的服", "mock-friend", build_friend(), TWO_LIBS, pinyin=True, persons=friend_persons(), image_scale=2)),
                            (3, Catalog("公益服 A", "mock-pub-a", build_public_a(), TWO_LIBS[:1], ignore_ids=True, refuse_favorites=True, delay=1.2)),
                            (4, Catalog("公益服 B", "mock-pub-b", build_public_b(), TWO_LIBS[:1], hang=25)),
                            (5, Catalog("无 ID 服", "mock-noid", build_noid(), TWO_LIBS[:1])),
                            (6, Catalog("严格服", "mock-strict", ITEMS, VIEWS, official_only=True))):
            servers.append(serve("0.0.0.0", a.port + offset, Emby, cat))
            print(f"mock emby {cat.name!r} on :{a.port + offset}", flush=True)
    for s in servers[1:]:
        threading.Thread(target=s.serve_forever, daemon=True).start()
    print(f"mock emby on :{a.port}, zidoo api on :{a.zidoo_port}", flush=True)
    servers[0].serve_forever()


if __name__ == "__main__":
    main()
