# RE2/J 1.8

The unmodified `re2j-1.8.jar` is pinned for Android and standalone Java checks.
It has no runtime dependencies. Its upstream Go BSD-style license is retained
verbatim in [LICENSE](LICENSE) and must accompany binary distributions.

Verified on 2026-10-21 against these original sources:

- Artifact: https://repo.maven.apache.org/maven2/com/google/re2j/re2j/1.8/re2j-1.8.jar
- Maven SHA-1: https://repo.maven.apache.org/maven2/com/google/re2j/re2j/1.8/re2j-1.8.jar.sha1
- Version metadata: https://repo.maven.apache.org/maven2/com/google/re2j/re2j/maven-metadata.xml
- Source: https://repo.maven.apache.org/maven2/com/google/re2j/re2j/1.8/re2j-1.8-sources.jar
- License: https://raw.githubusercontent.com/google/re2j/re2j-1.8/LICENSE
- Syntax: https://github.com/google/re2/wiki/Syntax

JAR size: 114,335 bytes.
SHA-256: `7b52c72156dd7f98b3237a5b35c1d34fba381b21048c89208913ad80a45dfbd7`.
Official Maven SHA-1: `12c25e923e9e4fb1575a7640a2698745c6f19a94`.
License SHA-256:
`26a6133577cc8e48d7c002ac4d4a554786e7c0e1732e10f2db205e5e494c411b`.

The query engine uses RE2/J's `Pattern.compile`, `programSize` and
`matcher(CharSequence).find` APIs. Source inspection of `MatcherInput`,
`MachineInput` and `RE2` confirms that this UTF-16 matching route reads the
guarded `CharSequence` directly, including literal-prefix scans. Tests exercise
cancellation during these reads. Extraction or replacement APIs are unused.

Android's `java.util.regex` uses ICU and converts its input with `toString()`;
a guarded `CharSequence` cannot enforce cancellation there. That source was
checked at https://android.googlesource.com/platform/libcore/+/refs/heads/main/ojluni/src/main/java/java/util/regex/Matcher.java
on 2026-10-21. RE2 also avoids recursive backtracking. Backreferences and
lookaround are deliberately unsupported; see [core syntax](../../core/README.md).
