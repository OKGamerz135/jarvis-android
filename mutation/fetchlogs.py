#!/usr/bin/env python3
"""fetchlogs.py RUN_ID ARTDIR : read '@@' result lines from the run's job logs and rebuild res-<ID>/ for classify.py."""
import json, os, re, subprocess, sys
run, art = sys.argv[1], sys.argv[2]
def gh(path):
    return subprocess.run(['gh', 'api', path], capture_output=True, text=True, timeout=300)
out = subprocess.run(['gh', 'api', f'repos/OKGamerz135/jarvis-android/actions/runs/{run}/jobs', '--paginate', '-q', '.jobs[]|{id,name,status,conclusion}'], capture_output=True, text=True).stdout
jobs = [json.loads(l) for l in out.strip().split('\n') if l.strip()]
summary = {}
def w(p, t):
    os.makedirs(os.path.dirname(p), exist_ok=True); open(p, 'w').write(t)
for j in jobs:
    m = re.match(r'(det|conc) \((\w+)', j['name']); summary[j['name']] = (j['status'], j['conclusion'])
    if j['status'] != 'completed': continue
    if not m and j['name'] != 'control': continue
    log = gh(f"repos/OKGamerz135/jarvis-android/actions/jobs/{j['id']}/logs").stdout
    if j['name'] == 'control':
        w(f'{art}/control.log', log); continue
    i = m.group(2); d = f'{art}/res-{i}'
    for line in log.split('\n'):
        k = line.find('@@')
        if k < 0: continue
        tag, _, payload = line[k:].partition(' ')
        o = json.loads(payload)
        if tag == '@@DET':
            w(f'{d}/status', o['status']); w(f'{d}/patch.sha256', o['patch_sha256']); w(f'{d}/head.sha', o['head'])
            w(f'{d}/control.json', json.dumps(o['control'])); w(f'{d}/control.exit', o['control_exit'])
            w(f'{d}/mutated.json', json.dumps(o['mutated'])); w(f'{d}/mutated.exit', o['mutated_exit']); w(f'{d}/diff.numstat', o['numstat'])
        elif tag == '@@REP':
            rp = f"{d}/{o['label']}/rep{o['n']:02d}"; w(f'{rp}/result.json', json.dumps(o['result'])); w(f'{rp}/exit', o['exit'])
        elif tag == '@@CONC':
            w(f'{d}/status', o['status']); w(f'{d}/baseline.txt', o['baseline']); w(f'{d}/patch.sha256', o['patch_sha256']); w(f'{d}/head.sha', o['head']); w(f'{d}/diff.numstat', o['numstat'])
json.dump(summary, open(f'{art}/jobs.json', 'w'), indent=1)
print(len(jobs), 'jobs;', sum(1 for v in summary.values() if v[0] == 'completed'), 'completed')
