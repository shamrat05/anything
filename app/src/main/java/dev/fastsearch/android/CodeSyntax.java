package dev.fastsearch.android;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded, dependency-free lexical coloring. This never parses or executes a program. */
final class CodeSyntax {
    static final int MAX_TOKENS = 6000;
    static final int KEYWORD = 0, STRING = 1, COMMENT = 2, NUMBER = 3, NAME = 4, CHANGE = 5;
    private static final Map<String, Language> EXTENSIONS = new HashMap<>();
    private static final Map<String, Language> NAMES = new HashMap<>();

    enum Language {
        PLAIN("Plain text", "", 0),
        JAVA("Java", "abstract assert boolean byte case catch char class default do double enum extends final finally float implements import instanceof int interface long native new package private protected public record sealed short static strictfp super synchronized this throws transient var void volatile permits", 1),
        KOTLIN("Kotlin", "actual annotation by catch class companion const constructor crossinline data delegate enum expect external fun init inline inner internal lateinit noinline object open operator out override package private protected public reified sealed suspend tailrec typealias val var when", 1),
        JAVASCRIPT("JavaScript / TypeScript", "abstract async await case catch class const debugger declare default delete do enum export extends finally from function get implements import infer interface keyof let namespace never new of private protected public readonly require satisfies set static super switch this throw typeof undefined var void yield", 1),
        PYTHON("Python", "and as assert async await class def del elif except finally from global import in is lambda nonlocal not or pass raise try with yield None True False", 2),
        C("C / C++", "alignas alignof asm auto bool case char class concept const consteval constexpr constinit decltype default do double enum explicit extern float friend inline int long namespace new noexcept nullptr operator private protected public register requires restrict short signed sizeof static struct template thread_local typedef typename union unsigned using virtual void volatile", 1),
        CSHARP("C#", "abstract async await base bool byte class const decimal delegate dynamic enum event explicit extern foreach implicit in interface internal lock namespace new object operator out override params private protected public readonly record ref sealed sizeof stackalloc static string struct switch this using var void where yield", 1),
        RUST("Rust", "as async await crate dyn enum extern fn impl let loop match mod move mut pub ref self Self static struct super trait type union unsafe use where", 1),
        GO("Go", "case chan const default defer fallthrough func go goto import interface map package range select struct switch type var nil iota", 1),
        SWIFT("Swift", "actor any as associatedtype async await class defer deinit enum extension fallthrough func guard import init inout internal let mutating nil nonmutating open operator override private protocol public repeat self Self some static struct subscript switch throws typealias var where", 1),
        SHELL("Shell", "case coproc declare do done echo elif esac eval exec export fi function in local printf read readonly select set source trap typeset unset until then", 2),
        RUBY("Ruby", "alias and begin case class def defined do elsif end ensure false module next nil not or redo rescue retry self super then undef unless until when yield", 2),
        PHP("PHP", "array as case class const declare default do echo empty endforeach endif eval extends final finally fn foreach function global include include_once instanceof interface isset list match namespace new print private protected public require require_once return static switch trait unset use yield", 3),
        LUA("Lua", "and do elseif end function goto in local nil not or repeat then until", 4),
        SQL("SQL", "alter and as asc begin between by case commit create cross database default delete desc distinct drop end exists from group having inner insert into is join left like limit not offset on or order outer primary references right rollback select set table then union unique update values view when where with", 5),
        MARKUP("HTML / XML", "", 6),
        CSS("CSS", "important media supports keyframes layer import font-face container", 1),
        JSON("JSON", "", 1),
        CONFIG("Config / YAML", "on off yes no null true false", 2),
        OPENVPN("OpenVPN config", "", 10),
        BUILD("Build files", "add android arg buildscript classpath cmd copy defaultConfig dependencies entrypoint env expose from function healthcheck implementation include label maintainer onbuild plugins repositories run shell tasks user volume workdir", 3),
        MARKDOWN("Markdown", "", 7),
        DIFF("Diff", "", 8),
        PROTO("Protocol Buffers", "bool bytes double enum extend extensions fixed32 fixed64 float import int32 int64 map message oneof option package repeated required reserved returns rpc service sfixed32 sfixed64 sint32 sint64 stream string syntax uint32 uint64", 1),
        GENERIC("General code", "async await case class def do end export fn for function if import in let match module new package proc pub public return struct switch then trait type use val var where while yield", 9);

        final String label;
        final Set<String> keywords;
        final int family;
        Language(String label, String keywords, int family) {
            this.label = label;
            this.keywords = new HashSet<>(Arrays.asList(keywords.split(" ")));
            this.family = family;
        }
    }

    static {
        bind(Language.PLAIN, "txt text log csv tsv nfo srt vtt ass ssa license");
        bind(Language.JAVA, "java"); bind(Language.KOTLIN, "kt kts");
        bind(Language.JAVASCRIPT, "js jsx mjs cjs ts tsx mts cts");
        bind(Language.PYTHON, "py pyw pyi");
        bind(Language.C, "c h cc cpp cxx hh hpp hxx inl ipp");
        bind(Language.CSHARP, "cs"); bind(Language.RUST, "rs"); bind(Language.GO, "go");
        bind(Language.SWIFT, "swift"); bind(Language.SHELL, "sh bash zsh fish ksh");
        bind(Language.RUBY, "rb erb rake gemspec"); bind(Language.PHP, "php php3 php4 php5 phtml");
        bind(Language.LUA, "lua"); bind(Language.SQL, "sql");
        bind(Language.MARKUP, "html htm xhtml xml svg xsd xsl xslt plist xaml axml tld rss atom");
        bind(Language.CSS, "css scss sass less");
        bind(Language.JSON, "json jsonl ndjson geojson jsonc ipynb webmanifest");
        bind(Language.CONFIG, "yaml yml toml ini conf config cfg properties gitignore gitattributes gitconfig editorconfig env desktop prop service socket timer target rc");
        bind(Language.OPENVPN, "ovpn");
        bind(Language.PROTO, "proto");
        bind(Language.BUILD, "gradle cmake mk make dockerfile");
        bind(Language.MARKDOWN, "md markdown mdown mkd"); bind(Language.DIFF, "diff patch");
        bind(Language.GENERIC, "dart r jl zig nim hs lhs ml mli fs fsx ex exs erl hrl clj cljs groovy scala vb vbs ps1 psm1 bat cmd asm s smali graphql gql tf hcl vue svelte sol v vhdl tsql lisp scm rkt pas f f90 f95 tex rst adoc lean perl pl pm");
        names(Language.PLAIN, "readme license licence copying authors changelog notice todo");
        names(Language.BUILD, "makefile gnumakefile cmakelists.txt dockerfile containerfile justfile");
        names(Language.CONFIG, ".env .gitignore .gitattributes .gitconfig .editorconfig .npmrc .prettierrc .clang-format .clang-tidy");
        names(Language.SHELL, ".bashrc .profile .bash_profile .zshrc");
        names(Language.RUBY, "gemfile rakefile podfile");
        names(Language.PYTHON, "sconstruct");
    }

    private static void bind(Language l, String extensions) { for (String ext : extensions.split(" ")) EXTENSIONS.put(ext, l); }
    private static void names(Language l, String names) { for (String name : names.split(" ")) NAMES.put(name, l); }
    private static Language byName(String name) {
        if (name == null) return null;
        String key = name.toLowerCase(Locale.ROOT);
        Language exact = NAMES.get(key);
        if (exact != null) return exact;
        if (key.startsWith(".env.")) return Language.CONFIG;
        if (key.startsWith("dockerfile.") || key.startsWith("containerfile.")) return Language.BUILD;
        int dot = key.lastIndexOf('.');
        return dot < 0 ? null : EXTENSIONS.get(key.substring(dot + 1));
    }
    static boolean known(String name) { return byName(name) != null; }
    static Language detect(String name, String source) {
        Language known = byName(name);
        if (known != null && known != Language.PLAIN) return known;
        if (source.startsWith("#!")) {
            int newline = source.indexOf('\n');
            String hashbang = source.substring(0, newline < 0 ? Math.min(160, source.length()) : Math.min(160, newline));
            for (String word : hashbang.toLowerCase(Locale.ROOT).split("[\\s/]")) {
                if (word.startsWith("python")) return Language.PYTHON;
                if (word.startsWith("ruby")) return Language.RUBY;
                if (word.equals("node") || word.equals("bun") || word.equals("deno")) return Language.JAVASCRIPT;
                if (word.equals("sh") || word.equals("bash") || word.equals("zsh") || word.equals("fish") || word.equals("dash") || word.equals("ksh")) return Language.SHELL;
            }
        }
        return Language.PLAIN;
    }

    static final class Model {
        final String display;
        final Language language;
        final int[] spans, lineStarts;
        final boolean limited;
        Model(String display, Language language, int[] spans, int[] lineStarts, boolean limited) {
            this.display = display; this.language = language; this.spans = spans;
            this.lineStarts = lineStarts; this.limited = limited;
        }
        int memoryBytes() { return display.length() * 2 + (spans.length + lineStarts.length) * 4; }
    }

    static Model model(String name, String source, Language override) throws IOException {
        // Android lays out paragraphs on LF, not on CR. Keep the untouched text in PreviewText.Result for Copy text.
        String text = source.indexOf('\r') < 0 ? source : source.replace("\r\n", "\n").replace('\r', '\n');
        Language language = override == null ? detect(name, text) : override;
        int[] starts = new int[Math.min(32, PreviewText.MAX_LINES)]; int lines = 1;
        for (int i = 0; i < text.length(); ++i) {
            if ((i & 255) == 0) PreviewText.cancelled();
            if (text.charAt(i) != '\n' || i + 1 >= text.length() || lines >= PreviewText.MAX_LINES) continue;
            if (starts.length == lines) starts = Arrays.copyOf(starts, Math.min(PreviewText.MAX_LINES, lines * 2));
            starts[lines++] = i + 1;
        }
        Spans spans = new Spans();
        if (language != Language.PLAIN) scan(text, language, spans);
        return new Model(text, language, Arrays.copyOf(spans.data, spans.used), Arrays.copyOf(starts, lines), spans.limited);
    }

    private static final class Spans {
        int[] data = new int[96];
        int used;
        boolean limited;
        void add(int start, int end, int kind) {
            if (end <= start) return;
            if (used >= MAX_TOKENS * 3) { limited = true; return; }
            if (used + 3 > data.length) data = Arrays.copyOf(data, Math.min(MAX_TOKENS * 3, data.length * 2));
            data[used++] = start; data[used++] = end; data[used++] = kind;
        }
    }

    private static final Set<String> SHARED = new HashSet<>(Arrays.asList(
            "if", "else", "for", "while", "switch", "case", "return", "break", "continue", "try", "catch", "throw", "null", "true", "false"));

    private static void scan(String text, Language language, Spans out) throws IOException {
        int length = text.length(), family = language.family;
        for (int i = 0; i < length && !out.limited;) {
            PreviewText.cancelled();
            char c = text.charAt(i);
            boolean lineStart = i == 0 || text.charAt(i - 1) == '\n';
            if (family == 8) {
                int end = lineEnd(text, i);
                if (lineStart && (c == '+' || c == '-' || c == '@' || text.startsWith("diff ", i)))
                    out.add(i, end, c == '+' ? CHANGE : c == '-' ? STRING : KEYWORD);
                i = end == i ? i + 1 : end;
                continue;
            }
            if (family == 7) {
                if (lineStart && c == '#') {
                    int end = lineEnd(text, i); out.add(i, end, KEYWORD); i = end; continue;
                } else if (c == '\u0060') {
                    String delimiter = text.startsWith("\u0060\u0060\u0060", i) ? "\u0060\u0060\u0060" : "\u0060";
                    int end = markerEnd(text, i + delimiter.length(), delimiter);
                    out.add(i, end, STRING); i = end; continue;
                }
                i++; continue;
            }
            if (family == 10 && lineStart) {
                int start = spaces(text, i);
                if (start < length) {
                    char leader = text.charAt(start);
                    if (leader == '#' || leader == ';') {
                        int end = lineEnd(text, start); out.add(start, end, COMMENT); i = end; continue;
                    }
                    if (leader == '<') { i = scanTag(text, start, out); continue; }
                    if (Character.isLetter(leader)) {
                        int end = start + 1;
                        while (end < length && (wordChar(text.charAt(end)) || text.charAt(end) == '-')) end++;
                        out.add(start, end, KEYWORD); i = end; continue;
                    }
                }
            }
            if (family == 6) {
                if (text.startsWith("<!--", i)) {
                    int end = markerEnd(text, i + 4, "-->"); out.add(i, end, COMMENT); i = end; continue;
                }
                if (c == '<' && i + 1 < length) {
                    i = scanTag(text, i, out);
                    continue;
                }
                i++; continue;
            }
            if ((family == 1 || family == 3 || family == 9) && text.startsWith("/*", i)) {
                int end = markerEnd(text, i + 2, "*/"); out.add(i, end, COMMENT); i = end; continue;
            }
            if (family == 4 && text.startsWith("--[[", i)) {
                int end = markerEnd(text, i + 4, "]]"); out.add(i, end, COMMENT); i = end; continue;
            }
            if (family == 5 && text.startsWith("/*", i)) {
                int end = markerEnd(text, i + 2, "*/"); out.add(i, end, COMMENT); i = end; continue;
            }
            if (((family == 1 || family == 3 || family == 9) && text.startsWith("//", i))
                    || ((family == 2 || family == 3 || family == 9) && c == '#')
                    || ((family == 4 || family == 5) && text.startsWith("--", i))
                    || (language == Language.CONFIG && lineStart && c == ';')) {
                int end = lineEnd(text, i); out.add(i, end, COMMENT); i = end; continue;
            }
            if (c == '"' || c == '\'' || c == '\u0060' && language == Language.JAVASCRIPT) {
                boolean tripled = (language == Language.PYTHON || language == Language.KOTLIN || language == Language.JAVA)
                        && i + 2 < length && text.charAt(i + 1) == c && text.charAt(i + 2) == c;
                int end = quotedEnd(text, i, c, tripled, family == 5);
                int next = spaces(text, end);
                out.add(i, end, (language == Language.JSON && next < length && text.charAt(next) == ':') ? NAME : STRING);
                i = end; continue;
            }
            if (Character.isDigit(c) || c == '-' && language == Language.JSON && i + 1 < length && Character.isDigit(text.charAt(i + 1))) {
                int end = i + 1;
                while (end < length && numberPart(text.charAt(end), text.charAt(end - 1))) end++;
                out.add(i, end, NUMBER); i = end; continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '$' || c == '@') {
                int end = i + 1;
                while (end < length && (wordChar(text.charAt(end))
                        || (language == Language.CONFIG || language == Language.CSS) && text.charAt(end) == '-'
                        || language == Language.CONFIG && text.charAt(end) == '.')) end++;
                if (end - i <= 40) {
                    int next = language == Language.CONFIG ? spaces(text, end) : end;
                    String word = c == '@' ? text.substring(i + 1, end) : text.substring(i, end);
                    if (language == Language.SQL) word = word.toLowerCase(Locale.ROOT);
                    if (language == Language.CONFIG && next < length && (text.charAt(next) == ':' || text.charAt(next) == '='))
                        out.add(i, end, NAME);
                    else if (language.keywords.contains(word) || language == Language.BUILD && language.keywords.contains(word.toLowerCase(Locale.ROOT)) || SHARED.contains(word)) out.add(i, end, KEYWORD);
                    else if (c == '@' || c == '$' && language == Language.SHELL) out.add(i, end, NAME);
                }
                i = end; continue;
            }
            i++;
        }
    }

    private static int scanTag(String text, int start, Spans out) throws IOException {
        int i = start + 1, len = text.length();
        if (i < len && (text.charAt(i) == '/' || text.charAt(i) == '!' || text.charAt(i) == '?')) i++;
        int nameStart = i;
        while (i < len && tagChar(text.charAt(i))) i++;
        if (i == nameStart) return start + 1;
        out.add(start, i, KEYWORD);
        while (i < len && !out.limited) {
            PreviewText.cancelled();
            char c = text.charAt(i);
            if (c == '<') return i;
            if (c == '>') { out.add(i, i + 1, KEYWORD); return i + 1; }
            if (c == '"' || c == '\'') {
                int end = quotedEnd(text, i, c, false, false); out.add(i, end, STRING); i = end;
            } else if (tagChar(c)) {
                int end = i + 1;
                while (end < len && tagChar(text.charAt(end))) end++;
                out.add(i, end, NAME); i = end;
            } else i++;
        }
        return i;
    }

    private static boolean wordChar(char c) { return Character.isLetterOrDigit(c) || c == '_' || c == '$'; }
    private static boolean tagChar(char c) { return wordChar(c) || c == ':' || c == '.' || c == '-'; }
    private static boolean numberPart(char c, char before) {
        return Character.isDigit(c) || c == '.' || c == '_' || c == 'x' || c == 'X' || c == 'o' || c == 'O'
                || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F' || ((c == '+' || c == '-') && (before == 'e' || before == 'E'));
    }
    private static int quotedEnd(String text, int start, char quote, boolean triple, boolean doubled) {
        int open = triple ? 3 : 1;
        for (int i = start + open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) i++;
            else if (c == quote) {
                if (triple) {
                    if (i + 2 < text.length() && text.charAt(i + 1) == quote && text.charAt(i + 2) == quote) return i + 3;
                } else if (doubled && i + 1 < text.length() && text.charAt(i + 1) == quote) i++;
                else return i + 1;
            } else if (c == '\n' && !triple && quote != '\u0060') return i;
        }
        return text.length();
    }
    private static int spaces(String text, int index) {
        while (index < text.length() && (text.charAt(index) == ' ' || text.charAt(index) == '\t')) index++;
        return index;
    }
    private static int lineEnd(String text, int index) {
        int end = text.indexOf('\n', index);
        return end < 0 ? text.length() : end;
    }
    private static int markerEnd(String text, int index, String marker) {
        int end = text.indexOf(marker, index);
        return end < 0 ? text.length() : end + marker.length();
    }

    private CodeSyntax() { }
}
