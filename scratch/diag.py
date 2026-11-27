import sys, glob, xml.etree.ElementTree as ET
ns = "{http://schemas.android.com/apk/res/android}"
paths = glob.glob("app/build/intermediates/merged_manifests/release/**/AndroidManifest.xml", recursive=True)
print("MERGED-MANIFEST-PATHS", paths)
for p in paths:
    root = ET.parse(p).getroot()
    print("MERGED-MANIFEST-FILE", p)
    for t in ("uses-permission", "uses-permission-sdk-23", "permission", "uses-feature"):
        for e in root.iter(t):
            print("MM", t, e.get(ns + "name"), "required=" + str(e.get(ns + "required")))
    app = root.find("application")
    for t in ("activity", "activity-alias", "service", "receiver", "provider"):
        for e in app.iter(t):
            print("MM", t, e.get(ns + "name"), "exported=" + str(e.get(ns + "exported")),
                  "enabled=" + str(e.get(ns + "enabled")), "authorities=" + str(e.get(ns + "authorities")),
                  "intent-filter=" + str(e.find("intent-filter") is not None),
                  "meta=" + ",".join(m.get(ns + "name") + "->" + str(m.get(ns + "value") or m.get(ns + "resource")) for m in e.iter("meta-data")))
    print("MM-RAW-BEGIN")
    print(open(p, encoding="utf-8").read())
    print("MM-RAW-END")
