package dev.fastsearch.android;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class CodeSyntaxTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        check(CodeSyntax.known("Main.KT") && CodeSyntax.known("tsconfig.json") && CodeSyntax.known("Dockerfile.dev"), "known extensions and dot names");
        check(!CodeSyntax.known("file.abc123") && !CodeSyntax.known("file.apk"), "do not claim unsupported binaries");
        detect("Main.KT", "", CodeSyntax.Language.KOTLIN);
        detect("browser.ts", "", CodeSyntax.Language.JAVASCRIPT);
        detect("server.PYI", "", CodeSyntax.Language.PYTHON);
        detect("Dockerfile.dev", "", CodeSyntax.Language.BUILD);
        detect(".env.local", "", CodeSyntax.Language.CONFIG);
        detect("settings.TOML", "", CodeSyntax.Language.CONFIG);
        detect("Circuit.vhdl", "", CodeSyntax.Language.GENERIC);
        detect("download.ovpn", "", CodeSyntax.Language.OPENVPN);
        detect("build.PROP", "", CodeSyntax.Language.CONFIG);
        detect("settings.conf", "", CodeSyntax.Language.CONFIG);
        detect("contract.proto", "", CodeSyntax.Language.PROTO);
        detect("keys.properties", "", CodeSyntax.Language.CONFIG);
        detect("CMakeLists.txt", "", CodeSyntax.Language.BUILD);
        detect("README", "ordinary if and while", CodeSyntax.Language.PLAIN);
        detect("script.txt", "#!/usr/bin/env -S python3 -u\npass", CodeSyntax.Language.PYTHON);
        detect("unknown", "#!/bin/bash -x\nexit", CodeSyntax.Language.SHELL);
        detect("unknown", "regular text", CodeSyntax.Language.PLAIN);

        CodeSyntax.Model java = CodeSyntax.model("Main.java", "class Main {\r\n  String s = \"// not a comment\";\r  int a = 0x2a;\n  /* multi\nline */\n}", null);
        check(Arrays.equals(java.lineStarts, new int[]{0,13,46,62,73,81}), "line starts are logical lines across CR, LF, CRLF");
        span(java, "class", CodeSyntax.KEYWORD); span(java, "\"// not a comment\"", CodeSyntax.STRING);
        span(java, "0x2a", CodeSyntax.NUMBER); span(java, "/* multi\nline */", CodeSyntax.COMMENT);
        check(!java.display.contains("\r"), "display line endings normalized only");
        nonoverlap(java);
        check(CodeSyntax.model("Main.java", "class Main {}", CodeSyntax.Language.PLAIN).spans.length == 0, "plain override");
        CodeSyntax.Model override = CodeSyntax.model("log.txt", "def f(): # comment\n    return 42", CodeSyntax.Language.PYTHON);
        span(override,"def",CodeSyntax.KEYWORD); span(override,"# comment",CodeSyntax.COMMENT);
        check(CodeSyntax.model("log.txt", "if for class", null).spans.length == 0, "ordinary TXT defaults to plain");
        CodeSyntax.Model dense = CodeSyntax.model("compiled.js", repeat("true false ", 10000), null);
        check(dense.limited && dense.spans.length == CodeSyntax.MAX_TOKENS * 3 && dense.display.length() == 110000, "dense token work is capped without removing text");
        nonoverlap(dense);
        CodeSyntax.Model json = CodeSyntax.model("schema.json", "{\"name\": \"value\", \"enabled\": true, \"n\": -2.3e+10}", null);
        span(json, "\"name\"", CodeSyntax.NAME); span(json, "\"value\"", CodeSyntax.STRING);
        span(json, "true", CodeSyntax.KEYWORD); span(json, "-2.3e+10", CodeSyntax.NUMBER);
        CodeSyntax.Model sql = CodeSyntax.model("data.sql", "SELECT 'one''two' FROM the_table -- comment", null);
        span(sql, "SELECT", CodeSyntax.KEYWORD); span(sql, "'one''two'", CodeSyntax.STRING);
        span(sql, "-- comment", CodeSyntax.COMMENT);
        CodeSyntax.Model html = CodeSyntax.model("literal.html", "<script data-x=\"<>\">bad()</script><!-- end -->", null);
        span(html, "<script", CodeSyntax.KEYWORD); span(html, "data-x", CodeSyntax.NAME); span(html, "\"<>\"", CodeSyntax.STRING);
        span(html, "</script", CodeSyntax.KEYWORD); span(html, "<!-- end -->", CodeSyntax.COMMENT);
        check(html.display.equals("<script data-x=\"<>\">bad()</script><!-- end -->"), "literal markup untouched");
        CodeSyntax.Model vpn = CodeSyntax.model("synthetic.ovpn", "client\n  remote vpn.example 443\n  ; ignore\n<ca>\n-----BEGIN CERTIFICATE-----\n</ca>", null);
        span(vpn, "client", CodeSyntax.KEYWORD); span(vpn, "remote", CodeSyntax.KEYWORD);
        span(vpn, "443", CodeSyntax.NUMBER); span(vpn, "; ignore", CodeSyntax.COMMENT);
        span(vpn, "<ca", CodeSyntax.KEYWORD);
        CodeSyntax.Model props = CodeSyntax.model("build.prop", "ro.debuggable=0\n# Comment", null);
        span(props, "ro.debuggable", CodeSyntax.NAME); span(props, "# Comment", CodeSyntax.COMMENT);
        CodeSyntax.Model patch = CodeSyntax.model("target.diff", "@@ beginning\n-previous\n+current\n context", null);
        span(patch, "-previous", CodeSyntax.STRING); span(patch, "+current", CodeSyntax.CHANGE);
        CodeSyntax.Model markdown = CodeSyntax.model("guide.md", "# Title\nOpen \u0060code\u0060 next", null);
        span(markdown, "# Title", CodeSyntax.KEYWORD); span(markdown, "\u0060code\u0060", CodeSyntax.STRING);
        PreviewText.Result truncated = PreviewText.read(new ByteArrayInputStream(repeat("a\n", 2200).getBytes(StandardCharsets.UTF_8)));
        CodeSyntax.Model capped = CodeSyntax.model("big.txt", truncated.text, null);
        check(truncated.truncated && capped.lineStarts.length == PreviewText.MAX_LINES, "numbering follows 2,000-line preview cap");
        Thread.currentThread().interrupt();
        try {
            CodeSyntax.model("stopped.java", "class O", null); throw new AssertionError("ignored interruption");
        } catch (java.io.IOException expected) {
            check(Thread.currentThread().isInterrupted(), "lexing cancels without clearing interruption");
        } finally { Thread.interrupted(); }
        System.out.println("Code syntax checked " + assertions + " assertions");
    }
    private static void detect(String name, String text, CodeSyntax.Language wanted) {
        check(CodeSyntax.detect(name, text) == wanted, "detect " + name + " as " + wanted);
    }
    private static void span(CodeSyntax.Model model, String token, int wanted) {
        for (int i = 0; i < model.spans.length; i += 3) {
            if (model.spans[i + 2] == wanted && token.equals(model.display.substring(model.spans[i], model.spans[i + 1]))) {
                assertions++; return;
            }
        }
        throw new AssertionError("no matching span: "+token);
    }
    private static void nonoverlap(CodeSyntax.Model model) {
        int previous = 0;
        for (int i = 0; i < model.spans.length; i += 3) {
            check(model.spans[i] >= previous && model.spans[i] < model.spans[i + 1]
                    && model.spans[i + 1] <= model.display.length(), "sorted bounded spans");
            previous = model.spans[i + 1];
        }
    }
    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) builder.append(value);
        return builder.toString();
    }
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        assertions++;
    }
}
