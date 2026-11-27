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
java -cp "core/build/test-classes:$re2j_jar" dev.fastsearch.core.CleanerRulesTest
java -cp "core/build/test-classes:$re2j_jar" dev.fastsearch.core.CleanerRecommendationsTest
java -cp "core/build/test-classes:$re2j_jar" dev.fastsearch.core.DuplicateFinderTest
javac --release 8 -Xlint:all,-options -cp core/build/test-classes -d app/build/duplicate-review-classes app/src/main/java/dev/fastsearch/android/DuplicateReview.java app/src/test/java/dev/fastsearch/android/DuplicateReviewTest.java app/src/main/java/dev/fastsearch/android/DuplicateCleanupPreflight.java app/src/test/java/dev/fastsearch/android/DuplicateCleanupPreflightTest.java
java -cp "app/build/duplicate-review-classes:core/build/test-classes" dev.fastsearch.android.DuplicateReviewTest
java -cp "app/build/duplicate-review-classes:core/build/test-classes" dev.fastsearch.android.DuplicateCleanupPreflightTest
sdk_path="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_path" || ! -f "$sdk_path/platforms/android-36/android.jar" ]]; then
  echo 'Set ANDROID_HOME to an SDK containing platforms/android-36/android.jar.' >&2
  exit 1
fi
javac --release 8 -cp "$sdk_path/platforms/android-36/android.jar" -d app/build/protocol-classes core/src/main/java/dev/fastsearch/core/FileEntry.java app/src/main/java/dev/fastsearch/android/{IndexRootScanner,SupportLog}.java app/src/test/java/dev/fastsearch/android/{IndexProtocolTest,SupportLogTest}.java
java -cp app/build/protocol-classes dev.fastsearch.android.IndexProtocolTest
java -cp app/build/protocol-classes dev.fastsearch.android.SupportLogTest
python3 scripts/test-rootindex.py
python3 scripts/test-rootwatch.py
javac --release 8 -Xlint:all,-options -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/PreviewText.java app/src/main/java/dev/fastsearch/android/ZipDirectory.java app/src/test/java/dev/fastsearch/android/PreviewDataTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.PreviewDataTest
javac --release 8 -Xlint:all,-options -cp app/build/preview-test-classes -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/OfficePreview.java app/src/test/java/dev/fastsearch/android/OfficePreviewTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.OfficePreviewTest
javac --release 8 -Xlint:all,-options -cp app/build/preview-test-classes -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/CodeSyntax.java app/src/test/java/dev/fastsearch/android/CodeSyntaxTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.CodeSyntaxTest
javac --release 8 -Xlint:all,-options -cp app/build/preview-test-classes -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/ArchiveExtraction.java app/src/test/java/dev/fastsearch/android/ArchiveExtractionTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.ArchiveExtractionTest
javac --release 8 -Xlint:all,-options -cp app/build/preview-test-classes -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/StreamArchiveExtraction.java app/src/test/java/dev/fastsearch/android/StreamArchiveExtractionTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.StreamArchiveExtractionTest
xz_jar=third_party/xz/xz-1.12.jar
javac --release 8 -Xlint:all,-options -cp "$xz_jar:app/build/preview-test-classes" -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/SevenZipArchive.java app/src/test/java/dev/fastsearch/android/SevenZipArchiveTest.java
java -cp "$xz_jar:app/build/preview-test-classes" dev.fastsearch.android.SevenZipArchiveTest
javac --release 8 -Xlint:all,-options -d app/build/preview-test-classes core/src/main/java/dev/fastsearch/core/FileEntry.java app/src/main/java/dev/fastsearch/android/FileSections.java app/src/test/java/dev/fastsearch/android/FileSectionsTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.FileSectionsTest
javac --release 8 -Xlint:all,-options -d app/build/preview-test-classes core/src/main/java/dev/fastsearch/core/FileEntry.java app/src/main/java/dev/fastsearch/android/FileSubtypes.java app/src/test/java/dev/fastsearch/android/FileSubtypesTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.FileSubtypesTest
javac --release 8 -Xlint:all,-options -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/FolderTree.java app/src/test/java/dev/fastsearch/android/FolderTreeTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.FolderTreeTest
javac --release 8 -Xlint:all,-options -d app/build/preview-test-classes core/src/main/java/dev/fastsearch/core/FileEntry.java app/src/main/java/dev/fastsearch/android/StorageMapData.java app/src/test/java/dev/fastsearch/android/StorageMapDataTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.StorageMapDataTest
javac --release 8 -Xlint:all,-options -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/UpdateRoute.java app/src/test/java/dev/fastsearch/android/UpdateRouteTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.UpdateRouteTest
javac --release 8 -Xlint:all,-options -cp app/build/preview-test-classes -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/{ApkBinaryXml,ApkPackageContents,ApkResources}.java app/src/test/java/dev/fastsearch/android/{ApkBinaryXmlTest,ApkPackageContentsTest,ApkResourcesTest}.java
java -cp app/build/preview-test-classes dev.fastsearch.android.ApkPackageContentsTest
java -cp app/build/preview-test-classes dev.fastsearch.android.ApkBinaryXmlTest
java -cp app/build/preview-test-classes dev.fastsearch.android.ApkResourcesTest
javac --release 8 -Xlint:all,-options -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/FolderSizeWalk.java app/src/test/java/dev/fastsearch/android/FolderSizeWalkTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.FolderSizeWalkTest

javac --release 8 -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/AppFileAttribution.java app/src/test/java/dev/fastsearch/android/AppFileAttributionTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.AppFileAttributionTest

javac --release 8 -cp app/build/preview-test-classes -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/CleanerScopedWalk.java app/src/test/java/dev/fastsearch/android/CleanerScopedWalkTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.CleanerScopedWalkTest

javac --release 8 -Xlint:all,-options -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/SmaliFind.java app/src/test/java/dev/fastsearch/android/SmaliFindTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.SmaliFindTest
javac --release 8 -Xlint:all,-options -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/SmaliReferences.java app/src/test/java/dev/fastsearch/android/SmaliReferencesTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.SmaliReferencesTest

javac --release 8 -Xlint:all,-options -cp app/build/preview-test-classes -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/ApkInspectionFacts.java app/src/test/java/dev/fastsearch/android/ApkInspectionFactsTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.ApkInspectionFactsTest

javac --release 8 -Xlint:all,-options -cp app/build/preview-test-classes -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/ArchiveIntegrity.java app/src/test/java/dev/fastsearch/android/ArchiveIntegrityTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.ArchiveIntegrityTest

javac --release 8 -Xlint:all,-options -cp app/build/preview-test-classes -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/HexInspection.java app/src/test/java/dev/fastsearch/android/HexInspectionTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.HexInspectionTest

javac --release 8 -Xlint:all,-options -cp app/build/preview-test-classes -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/FileComparison.java app/src/test/java/dev/fastsearch/android/FileComparisonTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.FileComparisonTest

javac --release 8 -Xlint:all,-options -cp app/build/preview-test-classes -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/TarArchiveListing.java app/src/test/java/dev/fastsearch/android/TarArchiveListingTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.TarArchiveListingTest

javac --release 8 -Xlint:all,-options -d app/build/preview-test-classes app/src/main/java/dev/fastsearch/android/{DexEntryPicker,SmaliExportPlan}.java app/src/test/java/dev/fastsearch/android/DexExportPlanTest.java
java -cp app/build/preview-test-classes dev.fastsearch.android.DexExportPlanTest
