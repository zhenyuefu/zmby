#!/usr/bin/env python3
"""Counts the requests zmby sent to mock_emby.py, per scenario (performance plan R0 ④).

Run the mock with its output in a file, mark each scenario, then summarise:

    python3 -I tools/mock_emby.py --multi > /tmp/mock.log
    curl -s 'http://127.0.0.1:8096/_mock/mark?name=home-cold'   # before each scenario
    python3 -I tools/request_count.py /tmp/mock.log             # every scenario
    python3 -I tools/request_count.py /tmp/mock.log home-cold   # one scenario, in detail

A scenario runs from its mark to the next one. For each it prints the total (and images), the
requests per server, per endpoint kind (item ids folded, query parameters that change the meaning
tagged), and every URL sent more than once by the same server: the repeats the plan wants gone.
Mock control requests (/_mock/) and the fake Zidoo player API are not counted. Only mock data goes
into the log, so the output can be shared.
"""
import collections
import json
import re
import sys
from urllib.parse import parse_qs, urlparse

LINE = re.compile(r"^\[(.+?)\] (GET|POST|DELETE|HEAD) (\S+)")
TAGS = ("SearchTerm", "AnyProviderIdEquals", "PersonIds", "Ids", "ParentId")


def kind(url):
    """`/Users/{uid}/Items/{id}` style endpoint name with the query tags that change its meaning."""
    u = urlparse(url)
    path = re.sub(r"^/emby", "", u.path, flags=re.I)
    if "/Images/" in path:
        return "Images"
    k = path if path.endswith(("/Resume", "/Latest")) else re.sub(r"/Items/[^/]+$", "/Items/{id}", path)
    k = re.sub(r"/Users/[^/]+/", "/Users/{uid}/", k)
    k = re.sub(r"/Shows/[^/]+/", "/Shows/{id}/", k)
    k = re.sub(r"/(Items|PlayedItems|FavoriteItems)/[^/]+/(Similar|PlaybackInfo|UserData|ThemeMedia)?", lambda m: f"/{m[1]}/{{id}}/{m[2] or ''}", k).rstrip("/")
    q = parse_qs(u.query)
    for tag in TAGS:
        if tag in q:
            k += f" [{tag}]"
    filters = q.get("Filters", [""])[0]
    if "IsPlayed" in filters:
        k += " [played]"
    if "IsFavorite" in filters:
        k += " [fav]"
    if q.get("StartIndex", ["0"])[0] != "0":
        k += " [page>0]"
    return k


def scenarios(lines):
    """[(name, [(server, method, url)])] split at /_mock/mark requests; requests before the first mark are `(start)`."""
    out = [("(start)", [])]
    for line in lines:
        m = LINE.match(line)
        if not m:
            continue
        server, method, url = m.groups()
        if "/_mock/mark" in url:
            out.append((parse_qs(urlparse(url).query).get("name", ["?"])[0], []))
        elif "/_mock/" in url or "/ZidooVideoPlay/" in url:
            continue
        else:
            out[-1][1].append((server, method, url))
    return [s for s in out if s[0] != "(start)" or s[1]]


def summary(reqs):
    per = collections.Counter(s for s, _, _ in reqs)
    kinds = collections.Counter((m, kind(u)) for _, m, u in reqs)
    exact = collections.Counter(reqs)
    repeats = sorted(((n, s, u) for (s, _, u), n in exact.items() if n > 1), reverse=True)
    images = sum(1 for _, _, u in reqs if "/Images/" in u)
    return {"total": len(reqs), "images": images, "servers": dict(per), "kinds": kinds, "repeats": repeats}


def main(argv):
    if len(argv) < 2:
        print(__doc__.strip())
        return 2
    lines = open(argv[1], encoding="utf-8", errors="replace").read().splitlines()
    wanted = argv[2] if len(argv) > 2 else None
    as_json = "--json" in argv
    results = {}
    for name, reqs in scenarios(lines):
        if wanted and name != wanted:
            continue
        s = summary(reqs)
        results[name] = {"total": s["total"], "images": s["images"], "repeats": sum(n - 1 for n, _, _ in s["repeats"]), "servers": s["servers"]}
        if as_json:
            continue
        print(f"== {name}: {s['total']} requests ({s['images']} images), {results[name]['repeats']} repeated")
        print("   " + "  ".join(f"{k} {v}" for k, v in sorted(s["servers"].items())))
        if wanted:
            for (m, k), n in sorted(s["kinds"].items(), key=lambda x: -x[1]):
                print(f"{n:5d}  {m} {k}")
        for n, server, url in s["repeats"][: (40 if wanted else 5)]:
            print(f"  x{n}  [{server}] {url[:140]}")
    if as_json:
        print(json.dumps(results, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
