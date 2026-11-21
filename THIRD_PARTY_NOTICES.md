# Third-party notices

The project [MIT license](LICENSE) applies to the independently written Android app, query core, project scripts and original documentation. It does not replace the notices below for reference material or build tooling.

## Gradle wrapper

`gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` are Gradle wrapper components licensed under the **Apache License, Version 2.0**. The startup scripts retain their original 2015 copyright notices. The wrapper is configured to download Gradle **8.13** from <https://services.gradle.org/distributions/gradle-8.13-bin.zip>.

[licenses/Apache-2.0.txt](licenses/Apache-2.0.txt) is an exact copy of `META-INF/LICENSE` from the included wrapper JAR. Gradle's source and licensing information are available at <https://github.com/gradle/gradle>.

## RE2/J

The query engine includes unmodified RE2/J **1.8**, distributed under its Go BSD-style license. [third_party/re2j/LICENSE](third_party/re2j/LICENSE) retains the original notice; the same bytes are packaged in the APK under `assets/licenses/re2j-LICENSE.txt`. Artifact hashes, original Maven/upstream URLs and the guarded matching APIs are recorded in [third_party/re2j/README.md](third_party/re2j/README.md).

## Test photographs

Only the instrumentation APK and store screenshots use NASA sample images. They are not bundled in the production APK or AAB. Retrieved 2026-10-21 from the NASA Image and Video Library:

- Earthrise: https://images.nasa.gov/details/as08-14-2383 and its medium JPEG.
- Earth: https://images.nasa.gov/details/PIA18033 and its medium JPEG.
- Saturn: https://images.nasa.gov/details/PIA17172 and its medium JPEG.

The short silent test video is encoded from the Earthrise image. These are sample media, with no NASA endorsement or NASA identity assets in the app. See https://www.nasa.gov/nasa-brand-center/images-and-media/ for NASA media-use guidance.

## Native document previews

CommonMark Java 0.24.0 and its GFM tables extension are unmodified Maven artifacts under the BSD 2-Clause license. Original sources: https://github.com/commonmark/commonmark-java and https://repo.maven.apache.org/maven2/org/commonmark/. The original notice is retained in `third_party/commonmark/LICENSE.txt` and packaged under `assets/licenses/commonmark-LICENSE.txt`.

AndroidSVG 1.4 is an unmodified Maven artifact under the Apache License 2.0. Original sources: https://github.com/BigBadaboom/androidsvg and https://repo.maven.apache.org/maven2/com/caverock/androidsvg/1.4/. The original license is retained in `third_party/androidsvg/LICENSE.txt` and packaged under `assets/licenses/androidsvg-LICENSE.txt`. Vector rendering is bounded and disables external resource loading.

Retrieved 28 October 2026. Exact JAR digests are in `third_party/preview-sha256.txt`, checked by `scripts/check.sh`.
