#!/usr/bin/env python3
"""One-off probe (no app code): for today's matches (ESPN, the app's feed), how many get a channel from Yallakora / Filgoal."""
import json, re, urllib.request, datetime, html as H

UA = {'User-Agent': 'Mozilla/5.0 (Linux; Android 14; TV) AppleWebKit/537.36 Chrome/124 Safari/537.36', 'Accept-Language': 'ar,en;q=0.8'}
def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=25) as r:
        return r.read().decode('utf-8', 'replace')

now = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=3)
day = now.strftime('%Y-%m-%d')
tn = open('app/src/io/ouswis/tv/standalone/TeamNames.java', encoding='utf-8').read()
AR = {}
for en, ar in re.findall(r'"([^"|]+)\|([^"|]+)"', tn): AR[en.lower()] = ar

def norm(s):
    s = H.unescape(s)
    s = re.sub(r'[ً-ٟـ]', '', s)
    s = re.sub('[أإآ]', 'ا', s).replace('ة', 'ه').replace('ى', 'ي')
    s = re.sub(r'\b(ال)', '', s) if False else s
    s = re.sub(r'[^\w]', '', s)
    return s.replace('ال', '', 1) if s.startswith('ال') else s

src = open('app/src/io/ouswis/tv/standalone/UpcomingMatches.java', encoding='utf-8').read()
leagues = re.findall(r'"([a-z0-9_.]+)"', re.search(r'LEAGUES=\{(.*?)\};', src, re.S).group(1))
matches = []
for lg in leagues:
    try: d = json.loads(get('https://site.api.espn.com/apis/site/v2/sports/soccer/%s/scoreboard?dates=%s&limit=1000' % (lg, now.strftime('%Y%m'))))
    except Exception: continue
    for e in d.get('events', []):
        k = datetime.datetime.strptime(e['date'], '%Y-%m-%dT%H:%MZ') + datetime.timedelta(hours=3)
        if k.strftime('%Y-%m-%d') != day: continue
        t = {c['homeAway']: c['team'].get('displayName', '') for c in e['competitions'][0]['competitors']}
        matches.append({'lg': lg, 'home': t.get('home', ''), 'away': t.get('away', ''), 'ko': k.strftime('%H:%M')})
print('TODAY matches (ESPN):', len(matches))

def parse_yalla():
    b = get('https://www.yallakora.com/match-center?date=%s' % now.strftime('%m/%d/%Y'))
    out = []
    for blk in re.split(r'<div liveScoreMatchId=', b)[1:]:
        ch = re.search(r'<div class="channel[^"]*">([^<]*)</div>', blk)
        names = re.findall(r'<p>([^<]+)</p>', blk)
        tm = re.search(r'class="time">\s*([0-9:]+)', blk)
        if len(names) >= 2: out.append((names[0].strip(), names[1].strip(), (ch.group(1).strip() if ch else ''), tm.group(1) if tm else ''))
    return out
def parse_fil():
    b = get('https://www.filgoal.com/matches/?date=%s' % day)
    out = []
    for blk in b.split('<div class="cin_cntnr">')[1:]:
        names = re.findall(r'<strong>([^<]+)</strong>', blk)
        ch = re.search(r'fb_screen"></use></svg>([^<]*)</span>', blk)
        tm = re.search(r'-\s*([0-9]{2}:[0-9]{2})\s*</span>', blk)
        if len(names) >= 2: out.append((names[0].strip(), names[1].strip(), (ch.group(1).strip() if ch else ''), tm.group(1) if tm else ''))
    return out

for name, fn in (('YALLAKORA', parse_yalla), ('FILGOAL', parse_fil)):
    try: rows = fn()
    except Exception as e:
        print(name, 'failed', e); continue
    print('\n=====', name, 'rows', len(rows), 'with channel', sum(1 for r in rows if r[2]))
    idx = {}
    for h, a, ch, tm in rows: idx[(norm(h), norm(a))] = (ch, tm, h, a)
    found = 0
    for m in matches:
        h, a = AR.get(m['home'].lower(), ''), AR.get(m['away'].lower(), '')
        hit = idx.get((norm(h), norm(a))) or (idx.get((norm(a), norm(h))) if h and a else None)
        if hit and hit[0]: found += 1
        print('  %s %-22s %s v %s -> AR(%s,%s) -> %s' % (m['ko'], m['lg'], m['home'], m['away'], h or '?', a or '?', ('%s [%s]' % (hit[0], hit[1])) if hit else 'NO MATCH'))
    print(name, 'channel found for', found, 'of', len(matches))
    print('  source rows not matched (first 25):')
    used = set()
    for r in rows[:25]: print('    ', r)
