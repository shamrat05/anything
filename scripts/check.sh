#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
mkdir -p core/build/test-classes app/build/protocol-classes app/build/preview-test-classes
re2j_jar=third_party/re2j/re2j-1.8.jar
echo '7b52c72156dd7f98b3237a5b35c1d34fba381b21048c89208913ad80a45dfbd7  third_party/re2j/re2j-1.8.jar' | sha256sum -c -
sha256sum -c third_party/preview-sha256.txt
javac --release 8 -Xlint:all,-options -cp "$re2j_jar" -d core/build/test-classes core/src/main/java/dev/fastsearch/core/*.java core/src/test/java/dev/fastsearch/core/*.java
java -cp "core/build/test-classes:$re2j_jar" dev.fastsearch.core.SearchQueryTest
java -cp "core/build/test-classes:$re2j_jar" dev.fastsearch.core.RenameRulesTest
sdk_path="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_path" || ! -f "$sdk_path/platforms/android-36/android.jar" ]]; then
  echo 'Set ANDROID_HOME to an SDK containing platforms/android-36/android.jar.' >&2
  exit 1
fi
javac --release 8 -cp "$sdk_path/platforms/android-36/android.jar" -d app/build/protocol-classes core/src/main/java/dev/fastsearch/core/FileEntry.java app/src/main/java/dev/fastsearch/android/{IndexRootScanner,SupportLog}.java app/src/test/java/dev/fastsearch/android/{IndexProtocolTest,SupportLogTest}.java
java -cp app/build/protocol-classes dev.fastsearch.android.IndexProtocolTest
java -cp app/build/protocol-classes dev.fastsearch.android.SupportLogTest
python3 scripts/test-rootindex.py
javac --release 8 -Xlint:all,-options -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/PreviewText.java app/src/main/java/dev/fastsearch/android/ZipDirectory.java app/src/test/java/dev/fastsearch/android/PreviewDataTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.PreviewDataTest
javac --release 8 -Xlint:all,-options -d app/build/preview-test-classes core/src/main/java/dev/fastsearch/core/FileEntry.java app/src/main/java/dev/fastsearch/android/FileSections.java app/src/test/java/dev/fastsearch/android/FileSectionsTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.FileSectionsTest
javac --release 8 -Xlint:all,-options -d app/build/preview-test-classes core/src/main/java/dev/fastsearch/core/FileEntry.java app/src/main/java/dev/fastsearch/android/FileSubtypes.java app/src/test/java/dev/fastsearch/android/FileSubtypesTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.FileSubtypesTest
