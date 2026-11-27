These owner-authored fixtures are bytecode test data, never loaded or installed.
Compile both source files with javac --release 8, then run D8 --min-api 26
separately on NavigatorOrigin.class and NavigatorTarget.class. Store each
resulting classes.dex as origin.dex and target.dex under assets/dex-reference.
