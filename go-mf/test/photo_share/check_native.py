#!/usr/bin/env python3
"""Compile/run actual Android-independent Kotlin transfer/range code, offline.

JAVA_HOME and KOTLIN_LIB can point to a JDK and Kotlin compiler lib directory.
Otherwise uses an installed JDK and compiler from a locally cached Gradle distro.
No Android SDK, dependency downloads, or generated protocol copies are required.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
java_home = os.environ.get('JAVA_HOME')
java = str(Path(java_home)/'bin/java') if java_home else shutil.which('java')
if not java:
    java = next((str(p) for p in (Path.home()/'.jdks').glob('*/bin/java') if '17' in str(p)), None)
if not java:
    raise SystemExit('Set JAVA_HOME to a local JDK')
libs = os.environ.get('KOTLIN_LIB')
if not libs:
    compiler = next((Path.home()/'.gradle/wrapper/dists').glob('gradle-*/**/lib/kotlin-compiler-embeddable-*.jar'), None)
    libs = str(compiler.parent) if compiler else None
if not libs:
    raise SystemExit('Set KOTLIN_LIB to a local Kotlin compiler lib directory')
libs = Path(libs)
stdlib = next(libs.glob('kotlin-stdlib-[0-9]*.jar'))
annotations = next(libs.glob('annotations-*.jar'))
source = ROOT/'android-client/app/src/main/java/com/demo/imsdc/webrtc'
with tempfile.TemporaryDirectory(prefix='shared-media-jvm-') as output:
    subprocess.run([java,'-cp',str(libs/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib','-no-reflect','-classpath',str(stdlib)+os.pathsep+str(annotations),'-d',output,
        str(source/'SharedMediaFiles.kt'),str(source/'SharedMediaRange.kt'),str(Path(__file__).with_name('MediaTransferTest.kt'))],check=True)
    subprocess.run([java,'-cp',output+os.pathsep+str(stdlib),'MediaTransferTestKt'],check=True)
