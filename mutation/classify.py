#!/usr/bin/env python3
"""classify.py ARTDIR OUT.json : classify downloaded res-<ID> artifacts against mutation/registry.json (frozen matrix)."""
import ipaddress, json, os, re, sys
REG = json.load(open(os.path.join(os.path.dirname(__file__), 'registry.json')))
EX = REG['executable']
def is_assert(t): return bool(t) and (t.endswith('AssertionError') or t.endswith('ComparisonFailure'))
def keyset(msg): return {f'C{a}/{b}' for a, b in re.findall(r'\bC([1-4])/([a-z-]+):', msg or '')}
FR = re.compile(r'at (?:app//)?dev\.jarvis\.assistant\.net\.\w+\.(\w+)\(\w+\.kt:(\d+)\)')
def bare(msg, etype):
    # Gradle's message attribute starts with '<exception class>: '; the signature matches the bare message.
    p = (etype or '') + ': '
    return msg[len(p):] if p != ': ' and msg.startswith(p) else msg
def line_of(f):
    if f.get('line') is not None: return f['line']
    for l in f.get('stack_head') or []:
        mm = FR.search(l)
        if mm and mm.group(1) == f['test']: return int(mm.group(2))
    return None
def match(sig, f):
    k = sig['kind']; msg = f.get('message') or ''; f = dict(f, line=line_of(f))
    if k == 'A': return is_assert(f['etype']) and sig['text'] in msg and not (sig.get('forbid') and sig['forbid'] in msg)
    if k == 'S': return is_assert(f['etype']) and f['line'] == sig['line']
    if k == 'H': return is_assert(f['etype']) and bare(msg, f['etype']) == 'expected a GuardViolation' and f['line'] == sig['line']
    if k == 'E': return f['etype'] == sig['etype'] and sig['emsg'] in msg and f['line'] == sig['line']
    if k == 'N22': return is_assert(f['etype']) and keyset(msg) == set(sig['keys'])
    if k == 'ARANGE':
        mm = re.match(r'^(\S+) \((\d+\.\d+\.\d+\.\d+)\) must not be PUBLIC', bare(msg, f['etype']))
        return bool(is_assert(f['etype']) and mm and ipaddress.ip_network(mm.group(1)).subnet_of(ipaddress.ip_network(sig['cidr'])))
    raise ValueError(k)
def find(res, sig):
    for f in res.get('failing', []):
        if f['cls'] == sig['cls'] and f['prefix'] == sig['test']: return f
    return None
def judge(res, sigs, corr):
    """res: parsed JSON of one mutated run. Returns (verdict, detail)."""
    t = res.get('totals')
    if not t or t['tests'] == 0: return 'NO-RESULTS', 'no test results'
    f = find(res, sigs[0])
    if f is None: return 'PASS', 'designated test passed'
    if match(sigs[0], f): v, d = 'MATCH', 'signature matched'
    else:
        d = f"{f['etype']} line={line_of(f)} msg={(f['message'] or '')[:160]!r}"
        if sigs[0]['kind'] == 'N22' and keyset(f['message']): d += f" keys={sorted(keyset(f['message']))}"; v = 'PARTIAL'
        else: v = 'WRONG'
    return v, d
def load(p):
    try: return json.load(open(p))
    except Exception: return None
def rd(p, default=''):
    try: return open(p).read().strip()
    except Exception: return default
def classify_det(i, art):
    d = f'{art}/res-{i}'; r = EX[i]
    out = dict(id=i, cls='det', patch_sha256=rd(f'{d}/patch.sha256'), registry_sha256=r['sha256'], status_file=rd(f'{d}/status'))
    if not os.path.isdir(d): out['verdict'] = 'NO-ARTIFACT'; return out
    s = out['status_file']
    if 'CONTROL-FAIL' in s: out['verdict'] = 'CONTROL-FAIL'; return out
    if 'PATCH-FAIL' in s: out['verdict'] = 'INFRA/INVALID'; out['detail'] = s; return out
    m = load(f'{d}/mutated.json'); c = load(f'{d}/control.json')
    out['control_totals'] = c and c.get('totals'); out['mutated_totals'] = m and m.get('totals')
    out['mutated_exit'] = rd(f'{d}/mutated.exit')
    if out['patch_sha256'] != r['sha256']: out['verdict'] = 'INFRA/INVALID'; out['detail'] = 'patch hash mismatch'; return out
    if not m: out['verdict'] = 'INFRA/INVALID'; out['detail'] = 'no mutated.json'; return out
    v, det = judge(m, r['designated'], r['corroborating'])
    if v == 'NO-RESULTS':
        out['verdict'] = 'INFRA/INVALID'; out['detail'] = 'no test results (compile/init failure?) ' + s; return out
    out['verdict'] = {'MATCH': 'KILLED', 'PASS': 'SURVIVED', 'WRONG': 'WRONG-FAILURE', 'PARTIAL': 'PARTIAL'}[v]; out['detail'] = det
    des = r['designated'][0]
    fo = find(m, des)
    out['observed'] = fo and dict(etype=fo['etype'], line=line_of(fo), message=bare(fo['message'] or '', fo['etype'])[:600], keys=sorted(keyset(fo['message'])) or None)
    ct = (out['control_totals'] or {}).get('tests'); mt = (out['mutated_totals'] or {}).get('tests')
    out['test_count_consistent'] = (ct == mt)
    out['collateral'] = sorted(f"{f['cls']}.{f['prefix']}" for f in m['failing'] if not (f['cls'] == des['cls'] and f['prefix'] == des['test']))
    out['corroborating'] = {f"{c['cls']}.{c['test']}@{c.get('line')}": (lambda f: 'absent' if f is None else ('match' if match(c, f) else 'differs'))(find(m, c)) for c in r['corroborating']}
    return out
def classify_conc(i, art):
    d = f'{art}/res-{i}'; r = EX[i]
    out = dict(id=i, cls='conc', status_file=rd(f'{d}/status'), baseline=rd(f'{d}/baseline.txt'), registry_sha256=r['sha256'], patch_sha256=rd(f'{d}/patch.sha256'))
    if not os.path.isdir(d): out['verdict'] = 'NO-ARTIFACT'; return out
    if 'BASELINE-FAIL' in out['status_file'] or 'PATCH-FAIL' in out['status_file']: out['verdict'] = 'BASELINE-FAIL' if 'BASELINE' in out['status_file'] else 'INFRA/INVALID'; return out
    reps = []
    for n in range(1, 31):
        rp = f'{d}/mutant/rep{n:02d}'; res = load(f'{rp}/result.json'); ex = rd(f'{rp}/exit')
        if not res: reps.append(dict(rep=n, kind='INFRA', detail='no result.json')); continue
        if ex not in ('0', '1'): reps.append(dict(rep=n, kind='INFRA', detail=f'exit={ex}')); continue
        v, det = judge(res, r['designated'], [])
        kind = {'MATCH': 'KILL', 'PASS': 'PASS', 'WRONG': 'WRONG', 'PARTIAL': 'WRONG', 'NO-RESULTS': 'INFRA'}[v]
        reps.append(dict(rep=n, kind=kind, detail=det, exit=ex))
    out['reps'] = reps
    cnt = {k: sum(1 for x in reps if x['kind'] == k) for k in ('KILL', 'PASS', 'WRONG', 'INFRA')}
    out['counts'] = cnt
    if cnt['WRONG'] or cnt['INFRA'] or len(reps) != 30: out['verdict'] = 'INCONCLUSIVE'
    elif cnt['KILL'] == 30: out['verdict'] = 'KILLED'
    elif cnt['KILL'] == 0: out['verdict'] = 'SURVIVED'
    else: out['verdict'] = 'UNRELIABLE'
    return out
if __name__ == '__main__':
    art, outp = sys.argv[1], sys.argv[2]
    res = {i: (classify_conc if r['cls'] == 'conc' else classify_det)(i, art) for i, r in sorted(EX.items())}
    json.dump(res, open(outp, 'w'), indent=1)
    from collections import Counter
    print(Counter(v['verdict'] for v in res.values()))
