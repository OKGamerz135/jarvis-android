#!/usr/bin/env python3
"""Print results into the job log (artifact downloads are blocked in the review session), one JSON object per '@@' line."""
import json, os, sys
def rd(p):
    try: return open(p).read().strip()
    except Exception: return ''
def ld(p):
    try: return json.load(open(p))
    except Exception: return None
def slim(r):
    if not r: return r
    r = dict(r); r['failing'] = [dict(f, message=(f.get('message') or '')[:6000], stack_head=(f.get('stack_head') or [])[:6]) for f in r.get('failing', [])]
    r.pop('ran', None); return r
kind = sys.argv[1]
if kind == 'det':
    i, d = sys.argv[2], sys.argv[3]
    o = dict(id=i, head=rd(f'{d}/head.sha'), status=rd(f'{d}/status'), patch_sha256=rd(f'{d}/patch.sha256'),
             control_exit=rd(f'{d}/control.exit'), control=slim(ld(f'{d}/control.json')),
             mutated_exit=rd(f'{d}/mutated.exit'), mutated=slim(ld(f'{d}/mutated.json')), numstat=rd(f'{d}/diff.numstat'))
    print('@@DET ' + json.dumps(o, separators=(',', ':')))
elif kind == 'rep':
    i, label, n, d = sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
    o = dict(id=i, label=label, n=int(n), exit=rd(f'{d}/exit'), result=slim(ld(f'{d}/result.json')))
    print('@@REP ' + json.dumps(o, separators=(',', ':')))
elif kind == 'conc':
    i, d = sys.argv[2], sys.argv[3]
    o = dict(id=i, head=rd(f'{d}/head.sha'), status=rd(f'{d}/status'), baseline=rd(f'{d}/baseline.txt'), patch_sha256=rd(f'{d}/patch.sha256'), numstat=rd(f'{d}/diff.numstat'))
    print('@@CONC ' + json.dumps(o, separators=(',', ':')))
