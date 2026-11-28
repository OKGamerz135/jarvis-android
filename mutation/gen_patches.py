import difflib, hashlib, json, os, sys
R = '/home/claude/jarvis-android'
M = 'app/src/main/java/dev/jarvis/assistant/net/'
PD = R + '/mutation/patches'
cache = {}
def src(f):
    if f not in cache:
        t = open(f'{R}/{M}{f}').read()
        assert t.endswith('\n')
        cache[f] = t[:-1].split('\n')
    return cache[f]

def edit(f, start, end, expect, new):
    """replace 1-based inclusive lines start..end; `expect` must be a substring of the old text; new is list of lines"""
    L = src(f)
    old = '\n'.join(L[start-1:end])
    assert expect in old, (f, start, end, expect, old)
    out = L[:start-1] + new + L[end:]
    return f, L, out

def sub(f, line, a, b):
    L = src(f)
    assert L[line-1].count(a) == 1, (f, line, a, L[line-1])
    return edit(f, line, line, a, [L[line-1].replace(a, b)])

def delete(f, start, end, expect):
    return edit(f, start, end, expect, [])

def insert_after(f, line, expect, new):
    L = src(f)
    assert expect in L[line-1]
    return f, L, L[:line] + new + L[line:]

MUT = {}   # id -> (f, old, new)
def add(i, t): MUT[i] = t
GR='GuardReason.kt'; HR='HostRule.kt'; AP='AddressPolicy.kt'; GC='GuardCtx.kt'; NS='NetworkSnapshot.kt'
add('MR1', sub(GR,38,') : IOException(reason.name)',') : Exception(reason.name)'))
add('MR2', sub(GR,48,') : RuntimeException(reason.name)',') : java.io.IOException(reason.name)'))
add('MR3', insert_after(GR,24,'NETWORK_STATE_UNAVAILABLE,',['    EXTRA_REASON,']))
add('MD1', sub(GR,38,'IOException(reason.name)','IOException(reason.name + (category?.name ?: ""))'))
add('MH1', sub(HR,22,'if (raw.isEmpty() || raw.any { it.code > 0x7f }) return null','if (raw.isEmpty()) return null'))
add('MH3', sub(HR,25,'h.length > 253','h.length > 254'))
add('MH4', sub(HR,29,'l.length > 63','l.length > 64'))
add('MH5', sub(HR,27,'labels.size < 2','labels.size < 1'))
add('MH6', delete(HR,30,31,'val dd'))
add('MH7', delete(HR,33,33,'labels.last()'))
add('MH9', sub(HR,23,'raw.lowercase()','raw'))
add('MH10', sub(HR,14,'Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?")','Regex("[a-z0-9-]([a-z0-9-]*[a-z0-9])?")'))
add('MA1', sub(AP,101,'return AddressClass.NOT_IPV4','return AddressClass.PUBLIC'))
add('MA2', sub(AP,109,'?: AddressClass.PUBLIC','?: AddressClass.SPECIAL_PURPOSE'))
add('MA5', sub(AP,103,'((b[1].toInt() and 255) shl 16)','(b[1].toInt() shl 16)'))
add('MA6', sub(AP,109,'return best?.addressClass ?: AddressClass.PUBLIC','return if (best != null) AddressClass.PRIVATE else AddressClass.PUBLIC'))
add('MA8', sub(AP,107,'(v and e.mask) == e.network','v == e.network'))
for k in range(1,20):
    add('MAR%02d'%k, delete(AP,78+k,78+k,'entry('))
add('MC1', sub(GC,67,'first.compareAndSet(null, GuardViolation(reason, category))','first.set(GuardViolation(reason, category))'))
add('MC2', delete(GC,74,74,'for (r in toClose) closeQuietly(r)'))
add('MC3', delete(GC,56,56,'closeQuietly(resource)'))
add('MC4', delete(GC,77,77,'cancelHook()'))
add('MC5', sub(GC,75,'if (hookRan.compareAndSet(false, true)) {','if (true) {'))
add('MC6', sub(GC,42,'throw it','throw GuardViolation(GuardReason.BIND_FAILED)'))
add('MC7', edit(GC,86,90,'c.close()',['        c.close()']))
add('MC8', edit(GC,37,38,'incrementAndGet',[
 '    fun incrementLookups(): Int { val n = lookupCount.get() + 1; lookupCount.set(n); return n }',
 '    fun incrementNetworkPasses(): Int { val n = networkPassCount.get() + 1; networkPassCount.set(n); return n }']))
add('MC9', sub(GC,67,'first.compareAndSet(null, GuardViolation(reason, category))','if (first.get() == null) first.set(GuardViolation(reason, category))'))
add('MC10', edit(GC,50,54,'synchronized(lock)',[
 '        val poisoned: GuardViolation? = first.get()',
 '        if (poisoned == null) synchronized(lock) { resources.add(resource) }']))
add('MC11', edit(GC,76,80,'cancelHook()',['                cancelHook()']))
add('MN1', delete(NS,132,132,'verify()'))
add('MN2', delete(NS,138,138,'verify()'))
add('MN3', delete(NS,144,144,'verify()'))
add('MN4', delete(NS,149,149,'verify()'))
add('MN5', delete(NS,58,58,'other.dnsServers == dnsServers'))
add('MN7', sub(NS,78,'link.dnsServers.sorted()','link.dnsServers'))
add('MN8', edit(NS,59,60,'privateDnsActive',['            true']))
add('MN9', sub(NS,157,'?: ctx.violate(GuardReason.NETWORK_CHANGED)','?: return'))
add('MN10', sub(NS,179,'result ?: ctx.violate(GuardReason.NETWORK_CHANGED)','result ?: LinkState(interfaceName = null, dnsServers = emptyList(), privateDnsActive = false, privateDnsServerName = null)'))
add('MN11', sub(NS,167,'NETWORK_STATE_UNAVAILABLE','NETWORK_CHANGED'))
add('MN12', sub(NS,124,'?: return Baseline.NoNetwork','?: ctx.violate(GuardReason.NETWORK_CHANGED)'))
add('MN13', delete(NS,56,56,'other.network == network'))
add('MN14', delete(NS,57,57,'other.interfaceName == interfaceName'))
add('MN15', delete(NS,156,156,'if (finished) return'))
add('MN16', sub(NS,177,'NETWORK_STATE_UNAVAILABLE','NETWORK_CHANGED'))
add('MN17', delete(NS,150,150,'finished = true'))
assert len(MUT)==63, len(MUT)
reg = {}
for i,(f,old,new) in sorted(MUT.items()):
    p = f'{M}{f}'
    d = list(difflib.unified_diff(old, new, f'a/{p}', f'b/{p}', n=3, lineterm=''))
    text = '\n'.join(d) + '\n'
    hunks = sum(1 for x in d if x.startswith('@@'))
    assert hunks == 1, (i, hunks)
    path = f'{PD}/{i}.patch'
    open(path,'w').write(text)
    reg[i] = {'patch': f'mutation/patches/{i}.patch', 'file': p, 'sha256': hashlib.sha256(text.encode()).hexdigest(), 'hunks': hunks}
json.dump(reg, open(R+'/mutation/patch-index.json','w'), indent=1, sort_keys=True)
print(len(reg), 'patches')
