#!/usr/bin/env python3
"""One-off probe (no app code): the HTML structure of Yallakora and Filgoal around a match's channel."""
import re, urllib.request, datetime, html as H
UA = {'User-Agent': 'Mozilla/5.0 (Linux; Android 14; TV) AppleWebKit/537.36 Chrome/124 Safari/537.36', 'Accept-Language': 'ar,en;q=0.8'}
def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=25) as r:
        return r.read().decode('utf-8', 'replace')
now = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=3)
for name, url in [('YALLAKORA', 'https://www.yallakora.com/match-center?date=%s' % now.strftime('%m/%d/%Y')),
                  ('FILGOAL', 'https://www.filgoal.com/matches/?date=%s' % now.strftime('%Y-%m-%d'))]:
    b = get(url)
    print('\n=====', name, len(b))
    i = b.find('beIN SPORTS')
    print(b[max(0, i - 1800):i + 400])
    print('----- second')
    j = b.find('beIN SPORTS', i + 3000)
    print(b[max(0, j - 1800):j + 300])
