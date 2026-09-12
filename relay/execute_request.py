#!/usr/bin/env python3
"""Remote GitHub Actions executor for gh-http-bridge."""
from __future__ import annotations
import base64, ipaddress, json, os, re, socket, subprocess, sys, tempfile
from pathlib import Path
from urllib.parse import urlsplit

VERSION=1
SECRET_SLOT_RE=re.compile(r"^@secret:([1-8])$")
SENSITIVE_HEADERS={"authorization","proxy-authorization","cookie","x-api-key","x-auth-token","x-access-token"}

def emit(obj):
    sys.stdout.write(json.dumps(obj,ensure_ascii=False,separators=(",",":"))+"\n")

def fail(message,request_id="unknown"):
    emit({"version":VERSION,"id":request_id,"ok":False,"error":message,"status":None,"headers":[],"body_base64":""})
    raise SystemExit(1)

def public_ip_for(host,port):
    try: infos=socket.getaddrinfo(host,port,type=socket.SOCK_STREAM)
    except socket.gaierror as exc: raise ValueError(f"DNS resolution failed: {exc}") from exc
    seen=[]
    for info in infos:
        ip_text=info[4][0]
        if ip_text in seen: continue
        seen.append(ip_text)
        ip=ipaddress.ip_address(ip_text)
        if ip.is_global: return ip_text
    raise ValueError("target does not resolve to a public/global IP address")

def resolve_secret(value):
    m=SECRET_SLOT_RE.fullmatch(value)
    if not m: return value
    slot=m.group(1); secret=os.environ.get(f"GHHTTP_SECRET_{slot}","")
    if not secret: raise ValueError(f"secret slot {slot} is empty")
    return secret

def validate_manifest(req):
    if req.get("version")!=VERSION: raise ValueError("unsupported request version")
    rid=str(req.get("id") or "")
    if not re.fullmatch(r"[A-Za-z0-9._-]{8,96}",rid): raise ValueError("invalid request id")
    method=str(req.get("method") or "GET").upper()
    if not re.fullmatch(r"[A-Z]{1,16}",method): raise ValueError("invalid HTTP method")
    url=str(req.get("url") or ""); parts=urlsplit(url)
    if parts.scheme not in {"http","https"} or not parts.hostname: raise ValueError("only absolute http/https URLs are allowed")
    if parts.username or parts.password: raise ValueError("credentials in URL are forbidden")
    port=parts.port or (443 if parts.scheme=="https" else 80)
    if not (1<=port<=65535): raise ValueError("invalid port")
    return rid,method,port,public_ip_for(parts.hostname,port)

def main():
    if len(sys.argv)!=2:
        print("usage: execute_request.py REQUEST.json",file=sys.stderr); return 2
    req_path=Path(sys.argv[1])
    try: req=json.loads(req_path.read_text(encoding="utf-8"))
    except Exception as exc: fail(f"cannot read request: {exc}")
    rid=str(req.get("id") or "unknown")
    try:
        rid,method,port,pinned_ip=validate_manifest(req); parts=urlsplit(str(req["url"]))
        timeout=int(req.get("timeout_seconds",30)); max_bytes=int(req.get("max_bytes",1024*1024))
        if not 1<=timeout<=120: raise ValueError("timeout_seconds out of range")
        if not 1<=max_bytes<=10*1024*1024: raise ValueError("max_bytes out of range")
        headers=req.get("headers") or {}
        if not isinstance(headers,dict): raise ValueError("headers must be an object")
        rendered=[]
        for name,value in headers.items():
            if not isinstance(name,str) or not isinstance(value,str): raise ValueError("header names and values must be strings")
            if any(x in name or x in value for x in ("\r","\n")): raise ValueError("header contains newline")
            if name.lower() in SENSITIVE_HEADERS and not SECRET_SLOT_RE.fullmatch(value): raise ValueError(f"sensitive header {name!r} must use @secret:N")
            rendered.append(f"{name}: {resolve_secret(value)}")
        body_secret_slot=req.get("body_secret_slot")
        if body_secret_slot is not None:
            slot=int(body_secret_slot)
            if not 1<=slot<=8: raise ValueError("body_secret_slot out of range")
            secret=os.environ.get(f"GHHTTP_SECRET_{slot}","")
            if not secret: raise ValueError(f"secret slot {slot} is empty")
            body=secret.encode()
        else:
            body_b64=str(req.get("body_base64") or ""); body=base64.b64decode(body_b64,validate=True) if body_b64 else b""
        host=parts.hostname; resolve_addr=f"[{pinned_ip}]" if ":" in pinned_ip else pinned_ip; resolve_rule=f"{host}:{port}:{resolve_addr}"
        with tempfile.TemporaryDirectory(prefix="ghhttp-") as td:
            td=Path(td); hdr_in=td/"request-headers.txt"; body_in=td/"request-body.bin"; hdr_out=td/"response-headers.txt"; body_out=td/"response-body.bin"
            hdr_in.write_text("\n".join(rendered)+( "\n" if rendered else ""),encoding="utf-8"); body_in.write_bytes(body)
            cmd=["curl","--silent","--show-error","--request",method,"--url",str(req["url"]),"--proto","=http,https","--proto-redir","=http,https","--max-redirs","0","--connect-timeout",str(min(timeout,20)),"--max-time",str(timeout),"--resolve",resolve_rule,"--dump-header",str(hdr_out),"--output",str(body_out),"--write-out","%{http_code}"]
            if rendered: cmd += ["--header",f"@{hdr_in}"]
            if body or method in {"POST","PUT","PATCH"}: cmd += ["--data-binary",f"@{body_in}"]
            proc=subprocess.run(cmd,capture_output=True,text=True,timeout=timeout+5)
            st=(proc.stdout or "").strip(); status=int(st) if st.isdigit() else None
            response_body=body_out.read_bytes() if body_out.exists() else b""; truncated=len(response_body)>max_bytes; response_body=response_body[:max_bytes]
            raw=hdr_out.read_text(encoding="iso-8859-1",errors="replace") if hdr_out.exists() else ""; response_headers=[]
            for line in raw.splitlines():
                if not line or line.startswith("HTTP/"): continue
                if ":" in line:
                    name=line.split(":",1)[0].strip().lower(); response_headers.append(f"{line.split(':',1)[0]}: <redacted>" if name in {"set-cookie","authorization","proxy-authorization"} else line)
            emit({"version":VERSION,"id":rid,"ok":proc.returncode==0 and status is not None,"curl_exit":proc.returncode,"status":status,"headers":response_headers,"body_base64":base64.b64encode(response_body).decode("ascii"),"truncated":truncated,"error":(proc.stderr or "").strip()[:1000] if proc.returncode else None})
            return 0 if proc.returncode==0 else 1
    except subprocess.TimeoutExpired: fail("request execution timed out",rid)
    except Exception as exc: fail(str(exc),rid)
    return 1

if __name__=="__main__": raise SystemExit(main())
