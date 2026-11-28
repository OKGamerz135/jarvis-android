#!/usr/bin/env python3
"""Parse Gradle JUnit XML into a compact JSON: totals and every failing test with type, message, test-frame line."""
import glob, json, re, sys, xml.etree.ElementTree as ET
FRAME = re.compile(r'at (?:app//)?dev\.jarvis\.assistant\.net\.(\w+)\.(\w+)\((\w+\.kt):(\d+)\)')
def parse(xmldir):
    tot = dict(tests=0, failures=0, errors=0, skipped=0, files=0); failing = []; names = []
    for f in sorted(glob.glob(f'{xmldir}/TEST-*.xml')):
        s = ET.parse(f).getroot(); tot['files'] += 1
        for k in ('tests', 'failures', 'errors', 'skipped'): tot[k] += int(s.get(k, 0))
        for tc in s.iter('testcase'):
            cls = tc.get('classname').split('.')[-1]; name = tc.get('name'); names.append(f'{cls}.{name}')
            for tag in ('failure', 'error'):
                for fl in tc.findall(tag):
                    st = fl.text or ''
                    line = None
                    for mm in FRAME.finditer(st):
                        if mm.group(2) == name.replace('()', ''):
                            line = int(mm.group(4)); break
                    failing.append(dict(cls=cls, test=name.replace('()', ''), prefix=name.split('_')[0], tag=tag,
                                        etype=fl.get('type'), message=(fl.get('message') or '')[:20000], line=line,
                                        stack_head=st.split('\n')[:10]))
    return dict(totals=tot, failing=failing, ran=names)
if __name__ == '__main__':
    xmldir, out = sys.argv[1], sys.argv[2]
    try: r = parse(xmldir)
    except Exception as e: r = dict(error=repr(e), totals=None, failing=[], ran=[])
    json.dump(r, open(out, 'w'), indent=1)
    t = r['totals']; print(out, t, 'failing:', [(f['cls'], f['test']) for f in r['failing']][:6])
