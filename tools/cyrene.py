#!/usr/bin/env python3
"""Shared local/Actions build entry point. No third-party Python dependencies."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
GP = ROOT / 'graphics-probe'
APP = ROOT / 'orbis-java-app'
WORK = ROOT.parent
# Mesa's base revision, compat/porting-kit pins and host build tools.
MESA_LOCK = WORK / 'mesa-orbis/orbis-port/build.lock.json'
sys.path.insert(0, str(APP / 'tools'))
import runtime_package as rp


def digest(path, algorithm='sha256'):
    h = hashlib.new(algorithm)
    with Path(path).open('rb') as source:
        for chunk in iter(lambda: source.read(1024*1024), b''):
            h.update(chunk)
    return h.hexdigest()


def fetch(url, path, algorithm, expected):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        temporary = path.with_name(path.name+'.partial')
        try:
            urllib.request.urlretrieve(url, temporary)
            if digest(temporary, algorithm) != expected:
                raise ValueError('Download hash mismatch: '+url)
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)
    if digest(path, algorithm) != expected:
        raise ValueError('Cached dependency hash mismatch: '+str(path))


def extract(archive, destination):
    destination = Path(destination)
    with tarfile.open(archive, 'r:gz') as source:
        for member in source.getmembers():
            path = Path(member.name)
            if path.is_absolute() or '..' in path.parts or not (member.isfile() or member.isdir()):
                raise ValueError('Unsafe artifact member: '+member.name)
        source.extractall(destination, filter='data')


def runtime_files(root):
    root = Path(root)
    for name in ('release','lib/server/libjvm.prx'):
        if not (root/name).is_file():
            raise ValueError('Incomplete runtime export: '+str(root/name))
    return rp.inventory(root)


def verify_jdk(root, source, lock_sha, libc_sha):
    """source and lock_sha None: a released runtime, checked for libc and its own inventory only."""
    root=Path(root)
    report=json.loads((root/'build-provenance.json').read_text())
    files=runtime_files(root);files.pop('build-provenance.json',None)
    if report['libc_sha256']!=libc_sha or (source is not None and (report['lock_sha256']!=lock_sha or report['source']!=source)):
        raise ValueError('Runtime provenance mismatch: '+str(root))
    if report.get('output_files')!=files:
        raise ValueError('Runtime output inventory mismatch; preserve and rebuild: '+str(root))


class Runner:
    def __init__(self, logs):
        self.logs = Path(logs)
        self.logs.mkdir(parents=True, exist_ok=True)
    def run(self, name, args, cwd=None, env=None):
        path = self.logs / (name+'.log')
        print(f'[{name}] {shlex.join(map(str,args))}', flush=True)
        print('  log: '+str(path), flush=True)
        with path.open('a') as output:
            output.write('\n$ '+shlex.join(map(str,args))+'\n'); output.flush()
            if os.environ.get('GITHUB_ACTIONS') == 'true':
                with subprocess.Popen(list(map(str,args)), cwd=cwd, env=env,
                                      stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                      text=True, encoding='utf-8', errors='replace') as process:
                    for line in process.stdout:
                        output.write(line)
                        output.flush()
                        print(line, end='', flush=True)
                    code = process.wait()
            else:
                code = subprocess.run(list(map(str,args)), cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT).returncode
        if code:
            tail = '\n'.join(path.read_text(errors='replace').splitlines()[-25:])
            raise RuntimeError(f'{name} failed ({code}); see {path}\n{tail}')


def script(runner, name, path, *args, **kwargs):
    runner.run(name, [sys.executable, path, *args], **kwargs)


def bootstrap(runner):
    script(runner,'repositories',GP/'tools/repositories.py','bootstrap')


def dependencies(runner):
    lock = json.loads((GP/'lwjgl/lwjgl-sources.lock.json').read_text())
    for key, table in lock.items():
        if not key.startswith('java_artifacts'):
            continue
        for name, sha in table.items():
            if not name.endswith('.jar'):
                continue
            version = next(v for v in ('3.2.2','3.3.3','3.4.1','3.4.3') if '-'+v in name)
            artifact = name.split('-'+version)[0]
            fetch(f'https://repo.maven.apache.org/maven2/org/lwjgl/{artifact}/{version}/{name}',
                  GP/'build/java-jars'/version/name, 'sha1', sha)
    inputs = json.loads((APP/'tools/runtime-package-inputs.json').read_text())
    for name, item in inputs['launcher_libraries'].items():
        url = 'https://repo.maven.apache.org/maven2/'+name
        fetch(url, ROOT/item['source'], 'sha256', item['sha256'])
        if name.startswith('org/lwjgl/'):
            fetch(url, GP/'build/java-jars/3.2.2'/Path(name).name, 'sha256', item['sha256'])
    for item in json.loads((ROOT/'tools/compiler-dependencies.json').read_text()):
        fetch(item['url'], GP/item['path'], 'sha256', item['sha256'])
    script(runner,'runner-libraries',APP/'tools/runtime_package.py','prepare-libraries')


def doctor(args, required_versions=(17,21,25)):
    missing = []
    for name in ('git','make','clang','clang++','llvm-ar','llvm-readelf','llvm-strip','cmake','ninja','autoconf','pkg-config','dotnet'):
        if not shutil.which(name): missing.append(name)
    paths = [args.sdk/'bin/orbis-clang',args.sdk/'bin/clang',args.toolchain/'link.x',
             args.toolchain/'bin/linux/create-fself',args.pkgtool]
    for v in required_versions:
        boot = getattr(args,'boot_jdk'+str(v))
        if boot is None:
            missing.append('BOOT_JDK'+str(v)); continue
        paths.extend((boot/'bin/java',boot/'bin/javac'))
        if (boot/'bin/java').exists():
            result = subprocess.run([str(boot/'bin/java'),'-version'],capture_output=True,text=True)
            import re
            if result.returncode or not re.search(r'version "'+str(v)+r'(?:[.\"])',result.stderr+result.stdout):
                missing.append('matching host JDK '+str(v)+': '+str(boot))
    missing.extend(str(p) for p in paths if not p.exists())
    if sys.version_info[:2] != (3,12): missing.append('Python 3.12 (pinned Mesa wheels)')
    if missing: raise ValueError('Missing prerequisites:\n  '+'\n  '.join(missing))
    print('Host tools, SDK paths and boot JDK versions found. Build recipes perform the ABI/hash audits.')


def jdks(args, runner):
    builder = WORK/'orbis-openjdk/build.py'
    versions = (17,21,25) if args.jdk == 'all' else (int(args.jdk),)
    for version in versions:
        output = WORK/'orbis-openjdk/output'/f'jdk{version}'
        if output.exists():
            check_installed_jdk(output,version,digest(args.toolchain/'lib/libc.a'))
            print(f'JDK {version}: reusing completed export with matching libc')
            continue
        script(runner,f'jdk{version}',builder,'build',str(version),'--boot-jdk',getattr(args,'boot_jdk'+str(version)),
               '--sdk',args.sdk,'--toolchain',args.toolchain,'--jobs',str(args.jobs))


# The runtimes the package carries. The launcher downloads the others from the same release.
RUNTIME_VERSIONS = ('17',)
PACKAGED_JDKS = tuple(map(int, RUNTIME_VERSIONS))
# Which installed runtimes came from an orbis-openjdk release, and which release.
JDK_RELEASE = WORK/'orbis-openjdk/output/release.json'


def runtime_asset(version):
    return 'jre'+version+'-orbis-x86_64.tar.xz'


def installed_jdk_release():
    return json.loads(JDK_RELEASE.read_text()) if JDK_RELEASE.is_file() else {'versions':{}}


def check_installed_jdk(root, version, libc_sha):
    """A released runtime must match its own inventory and the launcher's libc; a source build also its pins."""
    if str(version) in installed_jdk_release()['versions']:
        verify_jdk(root,None,None,libc_sha)
    else:
        lock=WORK/'orbis-openjdk/versions.lock.json'
        verify_jdk(root,json.loads(lock.read_text())['versions'][str(version)],digest(lock),libc_sha)


def extract_runtime(archive, version, destination):
    prefix='jdk'+version
    with tarfile.open(archive,'r:xz') as source:
        for member in source.getmembers():
            path=Path(member.name)
            if path.is_absolute() or '..' in path.parts or path.parts[:1]!=(prefix,) or not (member.isfile() or member.isdir()):
                raise ValueError('Unsafe runtime archive member: '+member.name)
        source.extractall(destination,filter='data')
    return Path(destination)/prefix


def github_request(url, accept='application/vnd.github+json'):
    request=urllib.request.Request(url,headers={'Accept':accept})
    token=os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
    if token:request.add_header('Authorization','Bearer '+token)
    return urllib.request.urlopen(request)


def jdk_versions(args):
    return RUNTIME_VERSIONS if args.jdk=='all' else (args.jdk,)


def jdk_release(args):
    """The orbis-openjdk release to install (the rolling download release unless --jdk-tag), with its files."""
    repository=repository_name(json.loads((ROOT/'repositories.lock.json').read_text())['components']['orbis-openjdk']['url'])
    if args.from_dir:
        # As written by `gh release view --json tagName,targetCommitish` beside the downloaded assets.
        meta=json.loads((args.from_dir/'release.json').read_text())
        tag,commit,directory=meta['tagName'],meta['targetCommitish'],args.from_dir
    else:
        url=f'https://api.github.com/repos/{repository}/releases/tags/'+(args.jdk_tag or 'download')
        with github_request(url) as response:meta=json.load(response)
        tag,commit=meta['tag_name'],meta['target_commitish']
        directory=ROOT/'dist/runtime-downloads'/tag;directory.mkdir(parents=True,exist_ok=True)
        assets={asset['name']:asset['url'] for asset in meta['assets']}
        for name in ['SHA256SUMS',*map(runtime_asset,jdk_versions(args))]:
            if name not in assets:raise ValueError(f'Release {tag} has no {name}')
            if not (directory/name).is_file():
                with github_request(assets[name],'application/octet-stream') as response:
                    (directory/(name+'.partial')).write_bytes(response.read())
                (directory/(name+'.partial')).replace(directory/name)
    sums={entry.lstrip('*'):sha for sha,entry in (line.split() for line in (directory/'SHA256SUMS').read_text().splitlines() if line.strip())}
    return {'repository':repository,'tag':tag,'commit':commit,'directory':directory,'sha256':sums}


def fetch_jdks(args):
    """Install the runtimes of orbis-openjdk's download release (or --jdk-tag)."""
    release=jdk_release(args)
    libc=digest(args.toolchain/'lib/libc.a')
    output=WORK/'orbis-openjdk/output'
    installed=installed_jdk_release()
    for version in jdk_versions(args):
        name=runtime_asset(version);target=output/('jdk'+version)
        if name not in release['sha256']:raise ValueError(f'Release {release["tag"]} lists no {name}')
        if target.exists():
            if installed['versions'].get(version)==release['sha256'][name]:
                print(f'JDK {version}: {release["tag"]} already installed');continue
            if version in installed['versions']:
                raise ValueError(f'JDK {version} from {installed["tag"]} is installed; remove {target} to install {release["tag"]}')
            check_installed_jdk(target,version,libc)
            print(f'JDK {version}: keeping the source-built export, not {release["tag"]}');continue
        archive=release['directory']/name
        if digest(archive)!=release['sha256'][name]:raise ValueError('Runtime release hash mismatch: '+str(archive))
        output.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.cyrene-runtime-',dir=output) as d:
            staged=extract_runtime(archive,version,d)
            if json.loads((staged/'build-provenance.json').read_text()).get('version')!=version:
                raise ValueError(f'{name} does not hold JDK {version}')
            verify_jdk(staged,None,None,libc)
            shutil.move(staged,target)
        installed={'repository':release['repository'],'tag':release['tag'],'commit':release['commit'],
                   'versions':{**installed['versions'],version:release['sha256'][name]}}
        JDK_RELEASE.write_text(json.dumps(installed,indent=2)+'\n')
        print(f'JDK {version}: installed {release["tag"]}/{name}')


LIBRARY_LOCK = ROOT/'tools/library-releases.lock.json'


def repository_name(url):
    import re
    match = re.fullmatch(r'https://github\.com/([^/]+/[^/]+?)(?:\.git)?', url or '')
    if not match:raise ValueError('Not a GitHub repository URL: '+str(url))
    return match.group(1)


def check_library_pins(lock=None):
    """Every port release is recorded for the revision repositories.lock.json pins."""
    import re
    lock=lock or json.loads(LIBRARY_LOCK.read_text())
    repos=json.loads((ROOT/'repositories.lock.json').read_text())['components']
    if lock.get('schema')!=1 or not lock.get('components'):raise ValueError('Unsupported library release lock')
    for name,item in lock['components'].items():
        if item.get('revision')!=repos[name]['revision'] or not re.fullmatch(r'[0-9a-f]{64}',item.get('sha256') or ''):
            raise ValueError(f'Library release is not pinned for {name} {repos[name]["revision"]}; publish its release and run ./orbis pin-libraries')
    return lock


def library_releases(lock):
    """Tag, asset and repository of each pinned port release, from the ports' own release.json."""
    repos=json.loads((ROOT/'repositories.lock.json').read_text())['components']
    releases=[]
    for name,item in lock['components'].items():
        component=repos[name]
        spec=json.loads((WORK/component['directory']/item['spec']).read_text())
        releases.append({'component':name,'repository':repository_name(component['url']),
                         'tag':spec['tag_prefix']+'-'+component['revision'][:12],'asset':spec['asset'],'sha256':item.get('sha256')})
    return releases


def check_platform_mesa(lock):
    """lwjgl-orbis's platform PRX must have been linked against the Mesa release the launcher ships."""
    pinned=json.loads((WORK/'lwjgl-orbis/dependencies.lock.json').read_text())['mesa-orbis']
    mesa=lock['components']['mesa-orbis']
    if pinned['revision']!=mesa['revision'] or pinned['sha256']!=mesa['sha256']:
        raise ValueError('lwjgl-orbis was built against a different Mesa release than mesa-orbis pins; update one of them')


def release_archive():
    import importlib.util
    spec=importlib.util.spec_from_file_location('release_archive',WORK/'orbis-runtime-support/release_archive.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


def fetch_libraries(args):
    lock=check_library_pins();check_platform_mesa(lock)
    marker=GP/'build/.library-releases.json'
    installed=json.loads(marker.read_text()) if marker.is_file() else {}
    unpack=release_archive().unpack
    for item in library_releases(lock):
        name=item['component']
        if installed.get(name)==item['sha256']:
            print(f'{name}: {item["tag"]} already installed');continue
        if args.from_dir:
            archive=args.from_dir/name/item['asset']
        else:
            archive=ROOT/'dist/library-downloads'/item['tag']/item['asset']
            fetch(f"https://github.com/{item['repository']}/releases/download/{item['tag']}/{item['asset']}",archive,'sha256',item['sha256'])
        unpack(archive,GP/'build',item['sha256'])
        installed[name]=item['sha256'];marker.write_text(json.dumps(installed,indent=2)+'\n')
        print(f'{name}: installed {item["tag"]}/{item["asset"]}')


def pin_libraries(args):
    if args.check:
        check_library_pins();print('Library release pins match repositories.lock.json');return
    lock=json.loads(LIBRARY_LOCK.read_text())
    repos=json.loads((ROOT/'repositories.lock.json').read_text())['components']
    for item in library_releases(lock):
        name=item['component']
        if args.from_dir:
            sums=(args.from_dir/name/'SHA256SUMS').read_text()
        else:
            with urllib.request.urlopen(f"https://github.com/{item['repository']}/releases/download/{item['tag']}/SHA256SUMS") as response:
                sums=response.read().decode()
        recorded={entry.lstrip('*'):sha for sha,entry in (line.split() for line in sums.splitlines() if line.strip())}
        if item['asset'] not in recorded:raise ValueError('Release '+item['tag']+' has no '+item['asset'])
        lock['components'][name].update(revision=repos[name]['revision'],sha256=recorded[item['asset']])
    check_platform_mesa(lock)
    LIBRARY_LOCK.write_text(json.dumps(lock,indent=2)+'\n')
    print('Pinned library releases: '+', '.join(item['tag'] for item in library_releases(lock)))


def facades(args, runner):
    for version in ('3.2.2','3.3.3','3.4.1','3.4.3'):
        script(runner,'facade-'+version,GP/'tools/build_lwjgl_facade.py','--release',version,'--boot-jdk',args.boot_jdk17,'--jars',GP/'build/java-jars'/version,'--fetch')


def native(args, runner):
    tools = GP/'tools'; build = GP/'build'
    if not (build/'deps/clean/bootstrap-manifest.json').exists():
        script(runner,'host-dependencies',tools/'bootstrap_deps.py','--output',build/'deps/clean')
    else:
        script(runner,'host-dependencies',tools/'bootstrap_deps.py','--output',build/'deps/clean','--verify-only')
    if not (build/'sources').exists():
        script(runner,'mesa-sources',tools/'prepare_sources.py','--lock',MESA_LOCK,'--output',build/'sources')
    runner.run('compat',['bash',build/'sources/orbis-compat/build.sh'],env={**os.environ,'OO_PS4_TOOLCHAIN':str(args.toolchain)})
    script(runner,'mesa',tools/'build_mesa.py','--sources',build/'sources','--sdk',args.toolchain,'--output',build/'vulkan-mesa')
    common = ['--sdk',args.sdk,'--toolchain',args.toolchain]
    script(runner,'platform',tools/'build_platform_prx.py',*common,'--jni-headers',args.boot_jdk17/'include',
           '--build',build/'vulkan-mesa','--output',build/'platform-native')
    for version in ('3.2.2','3.3.3','3.4.1','3.4.3'):
        script(runner,'lwjgl-'+version,tools/'build_lwjgl_natives.py',*common,'--jni-headers',args.boot_jdk17/'include','--release',version,'--fetch')
    script(runner,'optional',tools/'build_lwjgl_optional.py',*common,'--jni-headers',args.boot_jdk17/'include','--jars',build/'java-jars/3.2.2')
    script(runner,'openal',tools/'build_openal.py',*common,'--fetch')
    for version in ('3.3.3','3.4.1','3.4.3'):
        script(runner,'freetype-'+version,tools/'build_freetype.py',*common,'--release',version,'--fetch')
    script(runner,'spirv',tools/'build_spirv.py',*common,'--release','3.4.3','--fetch')
    script(runner,'vma',tools/'build_vma.py',*common,'--jni-headers',args.boot_jdk17/'include','--release','3.4.3','--jars',build/'java-jars/3.4.3','--fetch')
    script(runner,'sdl',tools/'build_sdl3.py',*common,'--fetch')
    facades(args,runner)


def launcher(args, runner):
    script(runner,'launcher',GP/'tools/build_launcher.py','--boot-jdk',args.boot_jdk17,'--java22-jdk',args.boot_jdk25,
           '--jars',GP/'build/java-jars/3.2.2','--gson',GP/'build/downloads/runner-jars/gson-2.8.9.jar')
    if not (APP/'build-cacio/source').exists():
        runner.run('cacio-source',['make','-C',APP,'cacio-source'])
    runner.run('cacio-export',['make','-C',APP,'cacio-export','BOOT_JDK='+str(args.boot_jdk17)])
    script(runner,'graphics-bundle',GP/'tools/package_graphics_bundle.py','--boot-jdk',args.boot_jdk17,
           '--platform',GP/'build/platform-native','--vma-sdl',GP/'build/vma-3.4.3','--output',GP/'build/graphics-bundle-native')


def component_paths():
    return [WORK/'orbis-openjdk/output'/f'jdk{v}' for v in PACKAGED_JDKS] + [GP/'build/graphics-bundle-native',APP/'build-cacio/export']


def inventory(root):
    root=Path(root)
    if not root.is_dir():raise ValueError('Missing component: '+str(root))
    files={}
    for p in sorted(root.rglob('*')):
        if p.is_symlink():raise ValueError('Link in component: '+str(p))
        if p.is_file():files[p.relative_to(root).as_posix()]=digest(p)
    if not files:raise ValueError('Empty component: '+str(root))
    return files


def verify_inventories(root, recorded):
    root=Path(root)
    for name,files in recorded.items():
        relative=Path(name)
        if relative.is_absolute() or '..' in relative.parts:raise ValueError('Unsafe component path: '+name)
        if inventory(root/relative)!=files:raise ValueError('Component inventory mismatch: '+name)


def record_components(args):
    lock=WORK/'orbis-openjdk/versions.lock.json'
    for v in PACKAGED_JDKS:
        check_installed_jdk(WORK/'orbis-openjdk/output'/f'jdk{v}',v,digest(args.toolchain/'lib/libc.a'))
    info=json.loads((GP/'build/graphics-bundle-native/build-info.json').read_text())
    if info['mesa_lock']!=json.loads(MESA_LOCK.read_text())['sources']:
        raise ValueError('Graphics source provenance mismatch')
    platform=json.loads((GP/'build/platform-native/platform-audit.json').read_text())
    if platform['graphics_drivers']!={'opengl':'radeonsi','vulkan':'radv','zink_compiled':False}:
        raise ValueError('Native driver audit missing')
    seal={'schema':1,'locks':{str(p.relative_to(WORK)):digest(p) for p in (ROOT/'repositories.lock.json',lock,MESA_LOCK)},
          'libc_sha256':digest(args.toolchain/'lib/libc.a'),
          'jdk_release':installed_jdk_release(),
          'components':{str(p.relative_to(WORK)):inventory(p) for p in component_paths()}}
    target=APP/'build-inputs/component-inventory.json';target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(seal,indent=2)+'\n')


def verify_components(toolchain=None, root=WORK, seal=None):
    if seal is None:seal=json.loads((APP/'build-inputs/component-inventory.json').read_text())
    if seal.get('schema')!=1:raise ValueError('Unsupported component inventory')
    expected_locks={str(p.relative_to(WORK)) for p in (ROOT/'repositories.lock.json',WORK/'orbis-openjdk/versions.lock.json',MESA_LOCK)}
    expected_components={str(p.relative_to(WORK)) for p in component_paths()}
    if set(seal['locks'])!=expected_locks or set(seal['components'])!=expected_components:
        raise ValueError('Unexpected component inventory layout')
    for name,sha in seal['locks'].items():
        if digest(WORK/name)!=sha:raise ValueError('Component lock mismatch: '+name)
    if toolchain:
        if digest(toolchain/'lib/libc.a')!=seal['libc_sha256']:
            raise ValueError('Component toolchain mismatch')
        expected=json.loads((ROOT/'tools/toolchain.lock.json').read_text())['toolchain_files']
        for name,sha in expected.items():
            if digest(toolchain/name)!=sha:raise ValueError('Component SDK mismatch: '+name)
    verify_inventories(root,seal['components'])
    return seal


def package_inputs():
    data=json.loads((APP/'tools/runtime-package-inputs.json').read_text())
    for v in PACKAGED_JDKS:
        item=data['runtimes'][f'jdk{v}'];item['source']=f'../orbis-openjdk/output/jdk{v}'
        item['files']=runtime_files(ROOT/item['source'])
    data['desktop']['files']=rp.inventory(ROOT/data['desktop']['source'])
    if not data['desktop']['files']:raise ValueError('Missing desktop export')
    data['graphics']['source']='graphics-probe/build/graphics-bundle-native'
    data['graphics']['manifest_sha256']=digest(ROOT/data['graphics']['source']/'graphics-bundle.json')
    # The revisions actually packaged: the repository pins, and for the runtimes the commit of the
    # installed orbis-openjdk release when they came from one.
    revisions={name:item['revision'] for name,item in json.loads((ROOT/'repositories.lock.json').read_text())['components'].items()}
    jdk=installed_jdk_release()
    if jdk['versions']:revisions['orbis-openjdk']=jdk['commit']
    data['component_revisions']=revisions
    target=APP/'build-inputs/runtime-package-inputs.local.json';target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(data,indent=2)+'\n')
    return target


def package(args,runner):
    verify_components(args.toolchain)
    inputs=package_inputs(); build=APP/'build-cyrene'
    runner.run('package',['make','-C',APP,'package','SDK='+str(args.sdk),'TOOLCHAIN='+str(args.toolchain),
                         'BOOT_JDK='+str(args.boot_jdk17),'JAVA22_JDK='+str(args.boot_jdk25),
                         'PKGTOOL='+str(args.pkgtool),'VERSION='+args.version,'TITLE=Cyrene Launcher',
                         'BUILD='+str(build),'RELEASE_INPUTS='+str(inputs)])
    script(runner,'verify-package',APP/'tools/runtime_package.py','verify','--stage',build/'stage')
    output=ROOT/'dist';output.mkdir(exist_ok=True)
    pkgs=list((build/'output').glob('*.pkg'))
    if len(pkgs)!=1:raise ValueError('Expected exactly one completed PKG')
    target=output/f'Cyrene-Launcher-{args.version}.pkg';shutil.copyfile(pkgs[0],target)
    shutil.copyfile(build/'stage/manifests/runtime-package.json',output/'runtime-package.json')
    shutil.copyfile(inputs,output/'runtime-package-inputs.json')
    (output/'SHA256SUMS').write_text(''.join(f'{digest(p)}  {p.name}\n' for p in sorted(output.iterdir()) if p.is_file() and p.name!='SHA256SUMS'))
    print('Verified package: '+str(target))


def artifacts(args):
    verify_components(args.toolchain)
    paths = [WORK/'orbis-openjdk/output'/f'jdk{v}' for v in PACKAGED_JDKS]
    paths += [GP/'build/graphics-bundle-native',APP/'build-cacio/export']
    for path in paths:
        if not path.is_dir():raise ValueError('Missing component: '+str(path))
        if path.name == 'graphics-bundle-native':
            manifest=json.loads((path/'graphics-bundle.json').read_text())
            for name, sha in manifest['files'].items():
                if digest(path/name)!=sha:raise ValueError('Graphics artifact hash mismatch: '+name)
        else:
            rp.inventory(path)
    archive=args.archive.resolve();archive.parent.mkdir(parents=True,exist_ok=True)
    with tarfile.open(archive,'w:gz') as out:
        for path in paths:out.add(path,arcname=str(path.relative_to(WORK)))
        out.add(APP/'build-inputs/component-inventory.json',arcname='metadata/component-inventory.json')
        for path in (ROOT/'repositories.lock.json',WORK/'orbis-openjdk/versions.lock.json'):
            out.add(path,arcname='metadata/'+path.name)
    archive.with_name(archive.name+'.sha256').write_text(digest(archive)+'  '+archive.name+'\n')
    print(archive)


def import_artifacts(args):
    expected=args.archive.with_name(args.archive.name+'.sha256').read_text().split()[0]
    if digest(args.archive)!=expected:raise ValueError('Component archive hash mismatch')
    with tempfile.TemporaryDirectory(prefix='.cyrene-import-',dir=WORK) as d:
        temporary=Path(d);extract(args.archive,temporary)
        for lock in (ROOT/'repositories.lock.json',WORK/'orbis-openjdk/versions.lock.json'):
            if digest(temporary/'metadata'/lock.name)!=digest(lock):raise ValueError('Component revision mismatch: '+lock.name)
        seal=json.loads((temporary/'metadata/component-inventory.json').read_text())
        verify_components(args.toolchain,temporary,seal)
        sources=[temporary/'orbis-openjdk/output'/f'jdk{v}' for v in PACKAGED_JDKS]
        sources += [temporary/ROOT.name/'graphics-probe/build/graphics-bundle-native',temporary/ROOT.name/'orbis-java-app/build-cacio/export']
        for source in sources:
            if not source.is_dir():raise ValueError('Missing archived component: '+str(source))
            if source.name.startswith('jdk'):runtime_files(source)
            destination=WORK/source.relative_to(temporary)
            if destination.exists():raise ValueError('Preserve existing output before import: '+str(destination))
        for source in sources:
            destination=WORK/source.relative_to(temporary);destination.parent.mkdir(parents=True,exist_ok=True);shutil.move(source,destination)
        target=APP/'build-inputs/component-inventory.json';target.parent.mkdir(parents=True,exist_ok=True)
        target.write_text(json.dumps(seal,indent=2)+'\n')
    print('Components imported; package assembly will verify every input.')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['doctor','bootstrap','build','jdks','fetch-jdks','native','fetch-libraries','pin-libraries','release-assets','assets','launcher','seal','package','export','import'])
    parser.add_argument('--jdk',choices=['all','17','21','25'],default='all',help='Select one runtime for the jdks or fetch-jdks command')
    parser.add_argument('--from',dest='from_dir',type=Path,help='Directory of downloaded release assets: JDK archives directly, port releases in one subdirectory per repository')
    parser.add_argument('--check',action='store_true',help='With pin-libraries: only verify the existing pins')
    parser.add_argument('--jdk-tag',help='With fetch-jdks or build: install this orbis-openjdk release instead of download')
    parser.add_argument('--sdk',type=Path,default=Path(os.environ.get('ORBIS_SDK') or '/home/wonder/ps4-payload-sdk'))
    parser.add_argument('--toolchain',type=Path,default=Path(os.environ.get('PS4_TOOLCHAIN') or '/home/wonder/PS4Toolchain'))
    parser.add_argument('--pkgtool',type=Path,default=Path(os.environ.get('PKGTOOL') or '/home/wonder/pkgtool-net8/PkgTool.Core.dll'))
    for v in (17,21,25):parser.add_argument('--boot-jdk'+str(v),type=Path,default=Path(os.environ['BOOT_JDK'+str(v)]) if os.environ.get('BOOT_JDK'+str(v)) else (Path('/usr/lib/jvm/java-17-openjdk-amd64') if v==17 else None))
    parser.add_argument('--jobs',type=int,default=min(os.cpu_count() or 1,4))
    parser.add_argument('--version',default='01.06')
    parser.add_argument('--archive',type=Path,default=ROOT/'dist/cyrene-components.tar.gz')
    args=parser.parse_args()
    if args.jdk != 'all' and args.command not in ('jdks','fetch-jdks'):parser.error('--jdk is only supported by the jdks and fetch-jdks commands')
    import re
    if args.jobs<1 or not re.fullmatch(r'\d{2}\.\d{2}',args.version):parser.error('Use positive jobs and a PS4 version such as 01.06')
    runner=Runner(ROOT/'build-logs')
    try:
        if args.command=='doctor':doctor(args)
        elif args.command=='bootstrap':bootstrap(runner)
        elif args.command=='export':artifacts(args)
        elif args.command=='import':bootstrap(runner);import_artifacts(args)
        elif args.command=='seal':bootstrap(runner);record_components(args)
        elif args.command=='fetch-jdks':bootstrap(runner);fetch_jdks(args)
        elif args.command=='pin-libraries':
            if not args.check:bootstrap(runner)
            pin_libraries(args)
        elif args.command=='fetch-libraries':bootstrap(runner);fetch_libraries(args)
        elif args.command=='release-assets':
            # Machine-readable; run bootstrap first so the ports' release.json files are present.
            for item in library_releases(check_library_pins()):
                print(item['component'],item['repository'],item['tag'],item['asset'])
        else:
            versions=((17,21,25) if args.jdk=='all' else (int(args.jdk),)) if args.command=='jdks' else ((17,) if args.command=='native' else (17,25))
            doctor(args,versions);bootstrap(runner);dependencies(runner)
            if args.command=='jdks':jdks(args,runner)
            if args.command=='build':fetch_jdks(args)
            if args.command=='native':native(args,runner)
            if args.command in ('build','assets'):fetch_libraries(args);facades(args,runner)
            if args.command in ('build','launcher','assets'):
                launcher(args,runner)
                if args.command != 'assets':record_components(args)
            if args.command in ('build','package'):package(args,runner)
        return 0
    except (ValueError,RuntimeError,OSError,KeyError,subprocess.CalledProcessError) as error:
        print(str(error),file=sys.stderr);return 1

if __name__=='__main__':sys.exit(main())
