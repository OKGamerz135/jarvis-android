#!/usr/bin/env python3
"""Builds mutation/registry.json: frozen matrix (63 executable + 6 excluded) with expected signatures. Frozen at 17330db."""
import json
idx = json.load(open('mutation/patch-index.json'))
GRC='GuardReasonTest'; HRT='HostRuleTest'; APT='AddressPolicyTest'; GCT='GuardCtxTest'; NST='NetworkSnapshotTest'
V='dev.jarvis.assistant.net.GuardViolation'
def A(cls,t,text,forbid=None): return dict(kind='A',cls=cls,test=t,text=text,forbid=forbid)
def S(cls,t,line): return dict(kind='S',cls=cls,test=t,line=line)
def H(cls,t,line): return dict(kind='H',cls=cls,test=t,line=line)
def E(cls,t,etype,emsg,line): return dict(kind='E',cls=cls,test=t,etype=etype,emsg=emsg,line=line)
def N22(keys): return dict(kind='N22',cls=NST,test='n22',keys=sorted(keys))
CP=['handle','interface','dns-set','private-dns-state','private-dns-name']
def allf(k): return [f'C{k}/{f}' for f in CP]
def onef(fs):
    fs = [fs] if isinstance(fs, str) else fs
    return [f'C{k}/{x}' for k in (1,2,3,4) for x in fs]
R={}
def m(i, des, cor=(), cls='det'): R[i]=dict(status='executable',cls=cls,designated=des,corroborating=list(cor))
m('MR1',S(GRC,'n1a',16)); m('MR2',S(GRC,'n1b',23)); m('MR3',S(GRC,'n4',75)); m('MD1',S(GRC,'n2',42))
m('MH1',A(HRT,'t04d','must be rejected')); m('MH3',A(HRT,'n13','254-char host is rejected')); m('MH4',A(HRT,'n13','64-char label is rejected'))
m('MH5',A(HRT,'t04a',"'localhost' must be rejected")); m('MH6',A(HRT,'t04a',"'ab--cd.com' must be rejected"))
m('MH7',A(HRT,'t04a',"'0x7f.0.0.1' must be rejected")); m('MH9',A(HRT,'t04b',"'API.Example.COM' must be accepted"))
m('MH10',A(HRT,'t04a',"'-a.com' must be rejected"))
m('MA1',A(APT,'t02a','::1 must be rejected')); m('MA2',A(APT,'t01a','8.8.8.8 should be allowed'))
m('MA5',A(APT,'t01a','93.184.216.34 should be allowed')); m('MA6',A(APT,'n5','0.0.0.0/8 (0.0.0.0)',forbid='must not be PUBLIC'))
m('MA8',A(APT,'t02a','127.0.0.1 must be rejected'))
CIDR=['0.0.0.0/8','10.0.0.0/8','172.16.0.0/12','192.168.0.0/16','100.64.0.0/10','127.0.0.0/8','169.254.0.0/16','168.63.129.16/32','224.0.0.0/4','192.0.0.0/24','192.0.2.0/24','192.31.196.0/24','192.52.193.0/24','192.88.99.0/24','192.175.48.0/24','198.18.0.0/15','198.51.100.0/24','203.0.113.0/24','240.0.0.0/4']
for k,c in enumerate(CIDR,1):
    i='MAR%02d'%k
    if i=='MAR08': m(i,S(APT,'n7',135))
    elif i=='MAR09': m(i,S(APT,'n36',228))
    else: m(i,dict(kind='ARANGE',cls=APT,test='n5',cidr=c,text='must not be PUBLIC'))
m('MC1',A(GCT,'n19','[MC1] the first violation must win')); m('MC2',A(GCT,'n15a','[MC2] resource 0 must be closed exactly once'))
m('MC3',A(GCT,'n15b','[MC3] the late resource must be closed immediately')); m('MC4',A(GCT,'n16','[MC4] the hook must run on the first violation'))
m('MC5',A(GCT,'n16','[MC5] the hook must not run again')); m('MC6',A(GCT,'n17','[MC6] check must throw the stored violation'))
m('MC7',E(GCT,'n20','java.lang.RuntimeException','close failed',213))
m('MC8',A(GCT,'n18','[MC8] lookups'),cls='conc'); m('MC9',A(GCT,'n14','[MC9] every racing caller must see the one stored violation'),cls='conc')
m('MC10',A(GCT,'n15c','[MC10] the racing resource must be closed exactly once'),cls='conc')
m('MC11',E(GCT,'n20','java.lang.IllegalStateException','hook failed',213))
m('MN1',N22(allf(1)),[H(NST,'t12c',106),H(NST,'t12d',118)]); m('MN2',N22(allf(2)),[H(NST,'t12e',131)])
m('MN3',N22(allf(3)),[H(NST,'t12f',143)]); m('MN4',N22(allf(4)),[H(NST,'n28',345)])
m('MN5',N22(onef('dns-set')),[H(NST,'n23',234)]); m('MN7',E(NST,'n23',V,'NETWORK_CHANGED',227))
m('MN8',N22(onef(['private-dns-state','private-dns-name'])))
m('MN9',H(NST,'n24',268)); m('MN10',H(NST,'n24',275))
m('MN11',A(NST,'n26','[MN11] C0 security exception on the first read'))
m('MN12',E(NST,'n21',V,'NETWORK_CHANGED',154))
m('MN13',N22(onef('handle')),[H(NST,'n25',293)]); m('MN14',N22(onef('interface')))
m('MN15',E(NST,'n27',V,'NETWORK_CHANGED',330)); m('MN16',A(NST,'n26','[MN11] C0 security exception on the second read'))
m('MN17',E(NST,'n27',V,'NETWORK_CHANGED',330))
assert len(R)==63
for i,v in R.items():
    v['patch']=idx[i]['patch']; v['sha256']=idx[i]['sha256']
    if isinstance(v['designated'],dict): pass
    v['designated']=[v['designated']] if isinstance(v['designated'],dict) else v['designated']
R['MN15']['duplicate_behavior_of']='MN17'; R['MN17']['duplicate_behavior_of']='MN15'
EXC={'MH2':'equivalent','MH8':'equivalent','MA3':'equivalent','MA4':'equivalent','MA7':'equivalent','MN6':'unobservable'}
full={'executable':R,'excluded':{k:dict(status='excluded',reason=v) for k,v in EXC.items()},
      'concurrency_tests':{'MC8':'n18_counters_are_atomic','MC9':'n14_first_violation_wins_when_threads_race','MC10':'n15c_a_resource_racing_with_a_violation_is_always_closed_exactly_once'}}
json.dump(full,open('mutation/registry.json','w'),indent=1,sort_keys=True)
print(len(R),'executable',len(EXC),'excluded',sum(1 for v in R.values() if v['cls']=='conc'),'conc')
