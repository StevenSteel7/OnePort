"""Tiny uiautomator helper: python3 ui.py tap|long|find <text-or-desc regex>"""
import re, subprocess, sys, time

def dump():
    subprocess.run(['adb', 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml'], capture_output=True)
    return subprocess.run(['adb', 'shell', 'cat', '/sdcard/ui.xml'], capture_output=True, text=True).stdout

def find(pat):
    for m in re.finditer(r'<node [^>]*>', dump()):
        n = m.group(0)
        t = re.search(r' text="([^"]*)"', n).group(1)
        d = re.search(r'content-desc="([^"]*)"', n).group(1)
        if re.fullmatch(pat, t) or re.fullmatch(pat, d):
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', re.search(r'bounds="([^"]*)"', n).group(1)))
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None

action, pat = sys.argv[1], sys.argv[2]
p = find(pat)
if not p:
    print('NOT FOUND', pat); sys.exit(1)
if action == 'tap':
    subprocess.run(['adb', 'shell', 'input', 'tap', str(p[0]), str(p[1])])
elif action == 'long':
    subprocess.run(['adb', 'shell', 'input', 'swipe', str(p[0]), str(p[1]), str(p[0]), str(p[1]), '900'])
print(action, pat, p)
