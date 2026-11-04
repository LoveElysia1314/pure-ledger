"""Create and verify a source ZIP from a committed version."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parents[1]
def git(*arguments,**kwargs):
    return subprocess.run(['git',*arguments],cwd=ROOT,check=True,capture_output=True,**kwargs).stdout

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--ref',default='HEAD');args=parser.parse_args()
    commit=git('rev-parse',args.ref+'^{commit}').decode().strip()
    properties=git('show',commit+':gradle.properties').decode()
    version=re.search(r'^versionName=(.+)$',properties,re.M).group(1)
    assert re.fullmatch(r'\d+\.\d+\.\d+',version)
    prefix=f'pure-ledger-{version}/';target=ROOT/f'artifacts/pure-ledger-{version}-source.zip';target.parent.mkdir(parents=True,exist_ok=True)
    git('archive','--format=zip','--prefix='+prefix,'--output='+str(target),commit)
    rows=[]
    for row in git('ls-tree','-rz',commit).split(b'\0'):
        if not row:continue
        meta,name=row.split(b'\t',1);mode,kind,digest=meta.split();assert kind==b'blob'
        rows.append((name.decode(),mode,digest))
    payload=git('cat-file','--batch',input=b''.join(digest+b'\n' for _,_,digest in rows))
    offset=0;blobs={}
    for name,mode,digest in rows:
        end=payload.index(b'\n',offset);header=payload[offset:end].split();assert header[:2]==[digest,b'blob']
        length=int(header[2]);offset=end+1;blobs[name]=payload[offset:offset+length];offset+=length+1
    assert offset==len(payload)
    with zipfile.ZipFile(target) as bundle:
        files={item.filename[len(prefix):]:item for item in bundle.infolist() if not item.is_dir()}
        assert set(files)==set(blobs) and bundle.testzip() is None
        for name,item in files.items():
            expected=blobs[name]
            if name.endswith('.bat'):expected=expected.replace(b'\r\n',b'\n').replace(b'\n',b'\r\n')
            assert bundle.read(item)==expected,name
        assert files['gradlew'].external_attr>>16&0o111
    result=dict(version=version,commit=commit,files=len(rows),bytes=target.stat().st_size,sha256=hashlib.sha256(target.read_bytes()).hexdigest(),git_contents_verified=True,gradlew_executable=True)
    folder=ROOT/'artifacts/validation';folder.mkdir(parents=True,exist_ok=True)
    (folder/'source-package.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result))

if __name__=='__main__':main()
