#!/usr/bin/env python3
"""One-off probe (no app code): which source names the broadcasting channel of today's matches in our region?
Reads today's matches from ESPN (the app's own feed), then tries candidate channel sources and reports how many matches each names a channel for."""
import json, re, sys, urllib.request, datetime

UA = {'User-Agent': 'Mozilla/5.0 (Linux; Android 14; TV) AppleWebKit/537.36 Chrome/124 Safari/537.36', 'Accept-Language': 'ar,en;q=0.8'}
def get(url, timeout=25):
    req = urllib.request.Request(url, headers=UA)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode('utf-8', 'replace')
    except Exception as e:
        return getattr(e, 'code', 0), 'ERR ' + repr(e)[:160]

now = datetime.datetime.utcnow() + datetime.timedelta(hours=3)   # Riyadh
day = now.strftime('%Y-%m-%d')
src = open('app/src/io/ouswis/tv/standalone/UpcomingMatches.java', encoding='utf-8').read()
leagues = re.findall(r'"([a-z0-9_.]+)"', re.search(r'LEAGUES=\{(.*?)\};', src, re.S).group(1))
print('Riyadh day', day, 'leagues', len(leagues))

matches = []
for lg in leagues:
    st, body = get('https://site.api.espn.com/apis/site/v2/sports/soccer/%s/scoreboard?dates=%s&limit=1000' % (lg, now.strftime('%Y%m')))
    if st != 200: continue
    for e in json.loads(body).get('events', []):
        k = datetime.datetime.strptime(e['date'], '%Y-%m-%dT%H:%MZ') + datetime.timedelta(hours=3)
        if k.strftime('%Y-%m-%d') != day: continue
        comp = e['competitions'][0]
        t = {c['homeAway']: c['team'].get('displayName', '') for c in comp['competitors']}
        matches.append({'league': lg, 'home': t.get('home', ''), 'away': t.get('away', ''), 'ko': k.strftime('%H:%M'),
                        'espn_bc': [b.get('names') for b in comp.get('broadcasts', [])] + [g.get('media', {}).get('shortName') for g in comp.get('geoBroadcasts', [])]})
print('TODAY matches from ESPN (the app feed):', len(matches))
for m in matches: print('  ', m['ko'], m['league'], '|', m['home'], 'v', m['away'], '| espn_broadcasts:', m['espn_bc'])

print('\n=== A. TheSportsDB eventstv (Soccer, today) ===')
st, body = get('https://www.thesportsdb.com/api/v1/json/3/eventstv.php?d=%s&s=Soccer' % day)
print('status', st, 'bytes', len(body)); print(body[:300].replace('\n', ' '))
try:
    ev = json.loads(body).get('tvevents') or []
    print('tv events', len(ev))
    for e in ev[:60]:
        print('  ', e.get('strTime'), e.get('strEvent'), '|', e.get('strChannel'), '|', e.get('strCountry'))
except Exception as e:
    print('not JSON', e)

for name, url in [
    ('B. Yallakora match-center', 'https://www.yallakora.com/match-center?date=%s' % now.strftime('%m/%d/%Y')),
    ('C. Kooora home', 'https://www.kooora.com/'),
    ('D. Filgoal matches', 'https://www.filgoal.com/matches/?date=%s' % day),
    ('E. Goal.com ar fixtures', 'https://www.goal.com/ar/%D9%82%D8%A7%D8%A6%D9%85%D8%A9/%D9%86%D8%AA%D8%A7%D8%A6%D8%AC-%D9%88%D9%85%D9%88%D8%A7%D8%B9%D9%8A%D8%AF-%D8%A7%D9%84%D9%85%D8%A8%D8%A7%D8%B1%D9%8A%D8%A7%D8%AA/1u3k4cc1v7sb12v7dhhq6cnsa'),
]:
    print('\n===', name, '===')
    st, body = get(url)
    print('status', st, 'bytes', len(body), 'url', url)
    hits = re.findall(r'[^<>\n]{0,60}(?:beIN|bein|بي ?ان|SSC|MBC|ثمانية|أبوظبي الرياضية|دبي الرياضية|الكأس)[^<>\n]{0,60}', body)
    print('channel-like strings:', len(hits))
    for h in hits[:15]: print('   ', h.strip())
