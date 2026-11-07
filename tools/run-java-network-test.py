#!/usr/bin/env python3
"""Use the environment's normal HTTPS proxy/trust with a JVM test client."""
import os
from pathlib import Path
import subprocess
import sys
import urllib.parse
import urllib.request

host,classpath,main,*args=sys.argv[1:]
java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
cmd=[java]
proxy=urllib.parse.urlparse(urllib.request.getproxies().get('https',''))
if proxy.hostname and not urllib.request.proxy_bypass(host):
    cmd+=['-Dhttps.proxyHost='+proxy.hostname,'-Dhttps.proxyPort='+str(proxy.port or 80)]
    trust=Path('/etc/ssl/certs/java/cacerts')
    if trust.exists():cmd+=['-Djavax.net.ssl.trustStore='+str(trust)]
cmd+=['-cp',classpath,main]+args
raise SystemExit(subprocess.call(cmd))
