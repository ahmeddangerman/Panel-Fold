#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/../../../.." && pwd)"
build_dir="$(mktemp -d /tmp/tablet-unrar-probe.XXXXXX)"
trap 'rm -rf "$build_dir"' EXIT
# Build the source list from CMake so the probe cannot silently test a different engine.
python3 - "$repo_dir" "$build_dir" <<'PY'
import concurrent.futures,pathlib,re,subprocess,sys
repo=pathlib.Path(sys.argv[1]); build=pathlib.Path(sys.argv[2]); cpp=repo/'app/src/main/cpp'
source_list=re.search(r'set\(UNRAR_SOURCES\s+(.*?)\)',(cpp/'CMakeLists.txt').read_text(),re.S).group(1).split()
sources=[str(cpp/'unrar'/source) for source in source_list]
flags=['g++','-O1','-std=c++11','-DRARDLL','-DSILENT','-D_FILE_OFFSET_BITS=64','-D_LARGEFILE_SOURCE','-DRAR_SMP','-pthread',
       '-I'+str(cpp),'-I'+str(cpp/'unrar')]
sources += [str(repo/'app/src/test/native/unrar_regression.cpp'),str(cpp/'rar_session.cpp')]
def compile_one(source):
    output=build/(pathlib.Path(source).name+'.o')
    subprocess.run([*flags,'-c',source,'-o',str(output)],check=True)
    return str(output)
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
    objects=list(executor.map(compile_one,sources))
subprocess.run(['g++','-pthread',*objects,'-o',str(build/'probe')],check=True)
subprocess.run([str(build/'probe'),str(repo/'app/src/androidTest/assets/native')],check=True)
PY
