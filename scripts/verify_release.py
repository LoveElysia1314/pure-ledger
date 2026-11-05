"""Check the APK, tests, native evidence, licenses and source distribution boundary."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import xml.etree.ElementTree as E
import zipfile
from audit_source import ROOT, audit

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native',required=True,type=Path,help='Native check output directory')
    parser.add_argument('--previous-apk',type=Path,help='Optional installed-lineage signer comparison')
    args=parser.parse_args()
    configFile=ROOT/'.private/developer.json'
    config=json.loads(configFile.read_text(encoding='utf-8-sig')) if configFile.exists() else {}
    sdk=Path(os.environ.get('ANDROID_HOME') or config['androidHome'])/'build-tools/36.0.0'
    executable='.exe' if os.name=='nt' else ''
    javaHome=os.environ.get('JAVA_HOME') or config.get('javaHome')
    java=str(Path(javaHome)/('bin/java'+executable)) if javaHome else 'java'
    out=ROOT/'artifacts/validation';out.mkdir(parents=True,exist_ok=True)
    apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    def run(arguments,name):
        result=subprocess.run(list(map(str,arguments)),capture_output=True,text=True,encoding='utf-8',errors='replace')
        (out/(name+'.log')).write_text(result.stdout+result.stderr,encoding='utf-8')
        assert result.returncode==0,name
        return result.stdout+result.stderr
    properties=dict(re.findall(r'^(versionName|versionCode|versionCodeBase|versionRevision)=(.+)$',(ROOT/'gradle.properties').read_text(),re.M))
    version=properties['versionName'];code=int(properties['versionCode'])
    major,minor,patch=map(int,version.split('.'));assert 0<=minor<100 and 0<=patch<100
    revision=int(properties.get('versionRevision','0'))
    assert 0<=revision<100
    assert code==int(properties.get('versionCodeBase','0'))+major*1_000_000+minor*10_000+patch*100+revision
    badging=run([sdk/('aapt2'+executable),'dump','badging',apk],'apk-metadata')
    assert f"package: name='io.github.localledger.app' versionCode='{code}' versionName='{version}'" in badging
    permissions=run([sdk/('aapt2'+executable),'dump','permissions',apk],'apk-permissions')
    assert 'android.permission.INTERNET' in permissions
    assert 'android.permission.REQUEST_INSTALL_PACKAGES' not in permissions
    manifest=E.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot()
    android='{http://schemas.android.com/apk/res/android}'
    assert manifest.find('application').get(android+'usesCleartextTraffic')=='false'
    assert {item.get(android+'name') for item in manifest.findall('uses-permission')}=={
        'android.permission.INTERNET','android.permission.SYSTEM_ALERT_WINDOW','android.permission.POST_NOTIFICATIONS'}
    signature=run([java,'-jar',sdk/'lib/apksigner.jar','verify','--verbose','--print-certs',apk],'apk-signature')
    assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
    digest=lambda text:re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)',text).group(1)
    if args.previous_apk:
        previous=run([java,'-jar',sdk/'lib/apksigner.jar','verify','--print-certs',args.previous_apk],'previous-signature')
        assert digest(previous)==digest(signature)
    run([sdk/('zipalign'+executable),'-c','-P','16','4',apk],'apk-alignment')
    with zipfile.ZipFile(apk) as bundle:
        assert bundle.testzip() is None and len(bundle.namelist())==len(set(bundle.namelist()))
        for name in ['GPL-3.0-only.txt','Apache-2.0.txt','Lucide.txt']:
            assert bundle.read('assets/licenses/'+name)==(ROOT/'app/src/main/assets/licenses'/name).read_bytes()
        assert bundle.read('assets/licenses/GPL-3.0-only.txt')==(ROOT/'LICENSE').read_bytes()
        for name in bundle.namelist():
            data=bundle.read(name)
            if name.endswith('.dex'):
                for forbidden in [b'Lcom/jarstones/',b'Llocalprobe/',b'ApprovedWorkbookProbe',b'ApprovedEnglishProbe',b'NativeBrandIdentityTest',b'NativeRuntimeTest',b'NativeEnglishLocaleTest',b'NativeUpdateUiTest',b'NativeUpgradeDataTest',b'EnglishTestApplication',b'SourceDetailsTest',b'ChineseTestApplication',b'ChineseCopyContext']:
                    assert forbidden not in data,(name,forbidden)
            if name.endswith('.so') and any(abi in name for abi in ['arm64-v8a','x86_64']):
                assert data[:6]==b'\x7fELF\x02\x01'
                offset=struct.unpack_from('<Q',data,32)[0];size,count=struct.unpack_from('<HH',data,54)
                for index in range(count):
                    start=offset+index*size
                    if struct.unpack_from('<I',data,start)[0]==1:
                        fileOffset,address=struct.unpack_from('<QQ',data,start+8)
                        assert struct.unpack_from('<Q',data,start+48)[0]>=16384 and (address-fileOffset)%16384==0,name
    def tests(directory):
        suites=[E.parse(path).getroot() for path in directory.glob('TEST-*.xml')];assert suites,directory
        assert all(int(suite.get(key))==0 for suite in suites for key in ['failures','errors','skipped'])
        return sum(int(suite.get('tests')) for suite in suites)
    local={'core':tests(ROOT/'core/build/test-results/test'),'android':tests(ROOT/'app/build/test-results/testDebugUnitTest')}
    native=args.native.resolve();summary=json.loads((native/'summary.json').read_text(encoding='utf-8'))
    apkHash=hashlib.sha256(apk.read_bytes()).hexdigest();assert summary['apk_sha256']['app']==apkHash
    from run_native_checks import GROUPS
    for group,(_,count) in GROUPS.items():
        log=(native/(group+'.log')).read_text(encoding='utf-8-sig')
        assert re.search(r'OK \('+str(count)+r' tests?\)',log) and 'FAILURES!!!' not in log and 'Process crashed' not in log
    assert summary['tests']==sum(count for _,count in GROUPS.values())
    display=json.loads((native/'display-state.json').read_text())
    for before,after in [('before','after'),('night_before','night_after'),('size_before','size_after'),('app_locales_before','app_locales_after')]:assert display[before]==display[after]
    runtime=json.loads((native/'runtime-state.json').read_text());assert runtime['before']==runtime['after']
    behavior=json.loads((native/'runtime/runtime-result.json').read_text())
    assert behavior['user_dismissed_notification'] and behavior['reopen_restored_notification']
    assert behavior['notification_brand_icon'] and behavior['quick_entry_note_focused']
    source=audit();(out/'source-audit.json').write_text(json.dumps(source,indent=2),encoding='utf-8')
    target=ROOT/f'artifacts/pure-ledger-{version}-debug.apk';shutil.copy2(apk,target)
    result=dict(version=version,code=code,package='io.github.localledger.app',apk=target.relative_to(ROOT).as_posix(),bytes=target.stat().st_size,sha256=apkHash,signer_sha256=digest(signature),local_tests=local,native_tests=summary['tests'],source_files=source['source_files'],checks={'signature':True,'same_signer_as_previous':bool(args.previous_apk),'zip_elf_16kb':True,'license_assets':True,'source_boundary':True,'native_apk_hash':True,'restored_system_state':True,'notification_dismiss_reopen':True},public_release=False)
    (out/'release-summary.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result))

if __name__=='__main__': main()
