"""Audit the distributable source tree and pinned icon provenance."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT=Path(__file__).resolve().parents[1]

def audit():
    listing=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT)
    names=sorted(set(name for name in listing.decode('utf-8').split('\0') if name))
    assert names, 'Empty source tree'
    forbidden={'.apk','.aab','.jks','.keystore','.p12','.pem','.key','.db','.sqlite','.sqlite3','.xlsx','.xls','.hprof','.zip','.class'}
    manifest={}
    for name in names:
        path=ROOT/name
        assert path.is_file() and path.resolve().is_relative_to(ROOT),name
        assert not any(part in {'.private','.tools','artifacts','archive','build','.gradle'} for part in Path(name).parts),name
        assert path.suffix.lower() not in forbidden,name
        assert path.name not in {'.env','keystore.properties'} and not path.name.startswith('.env.'),name
        if path.suffix.lower() in {'.der','.lineage'}:
            assert name in {'signing/legacy-certificate.der','signing/release-certificate.der','signing/release.lineage'},name
        data=path.read_bytes()
        if path.suffix.lower() in {'.kt','.java','.kts','.md','.py','.ps1','.xml','.properties','.yml','.yaml','.json'}:
            text=data.decode('utf-8-sig')
            assert not re.search(r'[A-Za-z]:[\\/](?:Users|drzqr)[\\/]',text),name
            if name.startswith(('app/src/main/','core/src/main/')) and path.suffix in {'.kt','.java'}:
                assert not any(token in text for token in ['com.jarstones','io.github.localledger.yifei','localprobe']),name
            if name.startswith('core/src/main/') and path.suffix=='.kt':
                assert not re.search(r'^import\s+android\.',text,re.M),name
        manifest[name]=hashlib.sha256(data).hexdigest()
    for name in ['README.md','LICENSE','ORIGINS.md','AGENTS.md','gradlew','gradlew.bat','docs/ARCHITECTURE.md','docs/RELEASING.md']:
        assert name in manifest,name
    icons=ROOT/'design/icons/lucide'
    provenance=json.loads((icons/'manifest.json').read_text(encoding='utf-8'))
    for entry in [provenance['license'],*provenance['icons'].values()]:
        assert hashlib.sha256((icons/entry['file']).read_bytes()).hexdigest()==entry['sha256'],entry['file']
    signing=ROOT/'signing/certificates.json'
    if signing.is_file():
        identity=json.loads(signing.read_text(encoding='utf-8'))
        for role in ['legacy','release']:
            assert manifest[f'signing/{role}-certificate.der']==identity[f'{role}_sha256'],role
        assert manifest['signing/release.lineage']==identity['lineage_sha256']
    return dict(source_files=len(manifest),source_manifest=manifest,icon_files=len(provenance['icons']),checks={'source_boundary':True,'core_android_independent':True,'icon_provenance':True})

if __name__=='__main__':
    result=audit();folder=ROOT/'artifacts/validation';folder.mkdir(parents=True,exist_ok=True)
    (folder/'source-audit.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps({key:value for key,value in result.items() if key!='source_manifest'}))
