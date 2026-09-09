#!/usr/bin/env python3
import argparse, json, os, random, time
from pathlib import Path

import sentencepiece as spm
import torch
import torch.nn as nn
import torch.nn.functional as F

VOCAB=16000
BLOCK=512
D_MODEL=720
HEADS=12
LAYERS=14
MLP_MULT=4

class Attention(nn.Module):
    def __init__(self):
        super().__init__()
        self.qkv=nn.Linear(D_MODEL,3*D_MODEL,bias=False)
        self.proj=nn.Linear(D_MODEL,D_MODEL,bias=False)
    def forward(self,x):
        b,t,c=x.shape
        qkv=self.qkv(x).view(b,t,3,HEADS,c//HEADS)
        q,k,v=qkv.unbind(2)
        q,k,v=(z.transpose(1,2) for z in (q,k,v))
        y=F.scaled_dot_product_attention(q,k,v,is_causal=True)
        return self.proj(y.transpose(1,2).contiguous().view(b,t,c))

class Block(nn.Module):
    def __init__(self):
        super().__init__()
        self.ln1=nn.LayerNorm(D_MODEL)
        self.attn=Attention()
        self.ln2=nn.LayerNorm(D_MODEL)
        self.mlp=nn.Sequential(nn.Linear(D_MODEL,D_MODEL*MLP_MULT,bias=False),nn.GELU(),nn.Linear(D_MODEL*MLP_MULT,D_MODEL,bias=False))
    def forward(self,x):
        x=x+self.attn(self.ln1(x))
        return x+self.mlp(self.ln2(x))

class Model(nn.Module):
    def __init__(self):
        super().__init__()
        self.tok=nn.Embedding(VOCAB,D_MODEL)
        self.pos=nn.Embedding(BLOCK,D_MODEL)
        self.blocks=nn.ModuleList([Block() for _ in range(LAYERS)])
        self.ln=nn.LayerNorm(D_MODEL)
        self.head=nn.Linear(D_MODEL,VOCAB,bias=False)
        self.head.weight=self.tok.weight
        self.apply(self._init)
    @staticmethod
    def _init(m):
        if isinstance(m,(nn.Linear,nn.Embedding)): nn.init.normal_(m.weight,0.0,0.02)
    def forward(self,ids):
        t=ids.shape[1]
        x=self.tok(ids)+self.pos(torch.arange(t,device=ids.device))[None]
        for b in self.blocks: x=b(x)
        return self.head(self.ln(x))

def train_tokenizer(corpus_txt,out_model):
    prefix=str(Path(out_model).with_suffix(""))
    spm.SentencePieceTrainer.train(input=corpus_txt,model_prefix=prefix,vocab_size=VOCAB,model_type="bpe",character_coverage=0.9995,input_sentence_size=500000,shuffle_input_sentence=True,bos_id=1,eos_id=2,unk_id=0,pad_id=3)

def corpus_to_text(jsonl,path):
    with open(path,"w",encoding="utf-8") as o:
        for line in open(jsonl,encoding="utf-8"):
            r=json.loads(line)
            if r.get("language")!="tr": continue
            t=(r.get("text") or "").strip()
            if t: o.write(t+"\n")

def encode_corpus(sp,jsonl):
    ids=[]
    for line in open(jsonl,encoding="utf-8"):
        r=json.loads(line)
        if r.get("language")!="tr": continue
        ids.extend(sp.encode(r.get("text") or "",out_type=int))
        ids.append(sp.eos_id())
    if len(ids)<BLOCK*4: raise RuntimeError("corpus too small")
    return torch.tensor(ids,dtype=torch.long)

def batch(data,device):
    hi=len(data)-BLOCK-1
    i=random.randint(0,hi)
    x=data[i:i+BLOCK][None].to(device)
    y=data[i+1:i+BLOCK+1][None].to(device)
    return x,y

def save(path,model,step,loss,tokenizer_name):
    cfg={"vocab_size":VOCAB,"block_size":BLOCK,"layers":LAYERS,"d_model":D_MODEL,"heads":HEADS,"mlp_mult":MLP_MULT,"params":sum(p.numel() for p in model.parameters())}
    state={k:v.detach().cpu().half() for k,v in model.state_dict().items()}
    torch.save({"stage":"continual-pretrain","step":step,"config":cfg,"state_dict":state,"loss":loss,"tokenizer":tokenizer_name},path)

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--data",required=True); ap.add_argument("--work",required=True); ap.add_argument("--seconds",type=int,default=15000); ap.add_argument("--lr",type=float,default=1.5e-4)
    a=ap.parse_args(); w=Path(a.work); w.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(max(1,os.cpu_count() or 1)); random.seed(42); torch.manual_seed(42); device=torch.device("cpu")
    text=w/"corpus.txt"; corpus_to_text(a.data,text)
    tok=w/"tokenizer.model"
    if not tok.exists(): train_tokenizer(str(text),str(tok))
    sp=spm.SentencePieceProcessor(model_file=str(tok)); data=encode_corpus(sp,a.data)
    m=Model().to(device); params=sum(p.numel() for p in m.parameters()); assert 98_000_000 <= params <= 101_000_000, params
    opt=torch.optim.AdamW(m.parameters(),lr=a.lr,weight_decay=0.1)
    ck=w/"latest.pt"; step=0
    if ck.exists():
        z=torch.load(ck,map_location="cpu",weights_only=False)
        m.load_state_dict(z["state_dict"]); step=int(z.get("step",0))
    start=time.time(); last_loss=None
    while time.time()-start < a.seconds:
        x,y=batch(data,device); opt.zero_grad(set_to_none=True)
        logits=m(x); loss=F.cross_entropy(logits.reshape(-1,VOCAB),y.reshape(-1)); loss.backward(); torch.nn.utils.clip_grad_norm_(m.parameters(),1.0); opt.step(); step+=1; last_loss=float(loss)
        if step%10==0: print(json.dumps({"step":step,"loss":last_loss,"params":params}),flush=True)
        if step%50==0: save(ck,m,step,last_loss,tok.name)
    save(ck,m,step,last_loss,tok.name)
    (w/"manifest.json").write_text(json.dumps({"version":1,"step":step,"loss":last_loss,"params":params,"tokenizer":"tokenizer.model","checkpoint":"latest.pt","language":"tr"},ensure_ascii=False),encoding="utf-8")
    print(json.dumps({"done":True,"step":step,"loss":last_loss,"params":params}),flush=True)
if __name__=="__main__": main()
