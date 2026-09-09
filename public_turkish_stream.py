#!/usr/bin/env python3
import argparse, html, json, random, re, time, urllib.error, urllib.parse, urllib.request

API="https://tr.wikipedia.org/w/api.php"
UA="Mozilla/5.0 (compatible; PublicTurkishCorpusBot/1.1; +https://github.com/)"
TR_MARKERS=set("çğıöşüÇĞİÖŞÜ")
COMMON={"ve","bir","bu","için","ile","olarak","olan","daha","çok","de","da","mi","mı","mu","mü","ama","veya","gibi","sonra","önce","tarafından","üzerine","hakkında"}

def get(params):
    q=urllib.parse.urlencode(params)
    req=urllib.request.Request(API+"?"+q,headers={"User-Agent":UA,"Accept":"application/json"})
    delay=2.0
    for attempt in range(7):
        try:
            with urllib.request.urlopen(req,timeout=30) as r:
                data=json.load(r)
            time.sleep(0.8)
            return data
        except urllib.error.HTTPError as e:
            if e.code!=429 or attempt==6: raise
            retry=e.headers.get("Retry-After")
            wait=float(retry) if retry and retry.isdigit() else delay
            time.sleep(min(wait,60.0)); delay=min(delay*2.0,60.0)

def clean(s):
    s=html.unescape(s or ""); s=re.sub(r"<[^>]+>"," ",s); s=re.sub(r"\[[0-9]+\]"," ",s); return re.sub(r"\s+"," ",s).strip()

def turkish_score(s):
    low=" "+re.sub(r"[^a-zA-ZçğıöşüÇĞİÖŞÜ ]"," ",s.lower())+" "; words=[w for w in low.split() if len(w)>1]
    if not words:return 0.0
    common=sum(1 for w in words if w in COMMON); marked=sum(ch in TR_MARKERS for ch in s); letters=sum(c.isalpha() for c in s)
    return (common/max(1,len(words)))*3.0+min(marked/max(1,letters),0.08)*8.0

def random_titles(n):
    out=[]
    while len(out)<n:
        j=get({"action":"query","format":"json","list":"random","rnnamespace":0,"rnlimit":min(20,n-len(out))})
        out.extend(x["title"] for x in j["query"]["random"])
    return out

def extracts(titles):
    for i in range(0,len(titles),10):
        batch=titles[i:i+10]
        j=get({"action":"query","format":"json","prop":"extracts","explaintext":1,"exsectionformat":"plain","redirects":1,"titles":"|".join(batch)})
        for p in j.get("query",{}).get("pages",{}).values(): yield p.get("title",""),clean(p.get("extract",""))

def main():
    ap=argparse.ArgumentParser(); ap.add_argument("--out",required=True); ap.add_argument("--max-items",type=int,default=250); a=ap.parse_args(); random.seed(time.time_ns())
    seen=set(); kept=0
    with open(a.out,"w",encoding="utf-8") as f:
        rounds=0
        while kept<a.max_items and rounds<25:
            rounds+=1
            for title,text in extracts(random_titles(min(60,a.max_items-kept+30))):
                if len(text)<400: continue
                text=text[:18000]; sig=hash(text[:1000])
                if sig in seen: continue
                seen.add(sig)
                if turkish_score(text)<0.12: continue
                f.write(json.dumps({"language":"tr","source":"tr.wikipedia.org","title":title,"text":text},ensure_ascii=False)+"\n"); kept+=1
                if kept>=a.max_items: break
    if kept<25: raise SystemExit("insufficient Turkish corpus")
    print(json.dumps({"ok":True,"items":kept,"language":"tr"},ensure_ascii=False))
if __name__=="__main__":main()
