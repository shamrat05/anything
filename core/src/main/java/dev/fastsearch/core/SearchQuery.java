package dev.fastsearch.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

/** Compiles the documented subset in core/README.md. Matching never touches disk. */
public final class SearchQuery {
    public static final class Options {
        public boolean matchCase;
        public boolean wholeWord;
        public boolean matchPath;
        public boolean regex;

        public Options() {}
    }

    private interface Node { boolean matches(FileEntry entry, PatternBudget budget); }
    private interface LongTest { boolean matches(long value); }
    private static final Node ALL = (entry, budget) -> true;
    private static final ThreadLocal<PatternBudget> BUDGET = new ThreadLocal<PatternBudget>() {
        @Override protected PatternBudget initialValue() { return new PatternBudget(); }
    };
    private final Node root;

    private SearchQuery(Node root) { this.root = root; }

    public static SearchQuery compile(String text, Options options) {
        String source = text == null ? "" : text;
        if (source.length() > 8192) throw new QueryException("Search is limited to 8192 characters.");
        Options flags = options == null ? new Options() : options;
        Context context = new Context(flags.matchCase, flags.wholeWord, flags.matchPath ? 1 : 0, false);
        // Everything's Search > Regex mode treats the entire field as one expression.
        if (flags.regex) return new SearchQuery(regex(source, context, 0, new Compilation()));
        return new SearchQuery(new Parser(source).parse(context));
    }

    public boolean matches(FileEntry entry) {
        if (entry == null) return false;
        PatternBudget budget = BUDGET.get();
        budget.reset();
        boolean result = root.matches(entry, budget);
        budget.check();
        return result;
    }

    private static final class Context {
        final boolean matchCase;
        final boolean wholeWord;
        final int path; // 0: auto when slash is present, 1: full path, -1: filename only.
        final boolean regex;
        Context(boolean matchCase, boolean wholeWord, int path, boolean regex) {
            this.matchCase = matchCase;
            this.wholeWord = wholeWord;
            this.path = path;
            this.regex = regex;
        }
    }

    private static final class Compilation {
        int regexInstructions;
        void add(int size, int position) {
            if (size > RegexLimits.MAX_PROGRAM || (regexInstructions += size) > 32768) throw RegexLimits.complex(position);
        }
    }

    private static Node combine(List<Node> nodes, boolean and) {
        if (nodes.size() == 1) return nodes.get(0);
        return (entry, budget) -> {
            for (Node node : nodes) {
                budget.spend(1);
                if (node.matches(entry, budget) != and) return !and;
            }
            return and;
        };
    }

    private static final class Parser {
        final String source;
        final LocalDate today = LocalDate.now();
        final ZoneId zone = ZoneId.systemDefault();
        final Compilation compilation = new Compilation();
        int position;
        int depth;
        int terms;

        Parser(String source) { this.source = source; }

        Node parse(Context context) {
            whitespace();
            if (end()) return ALL;
            Node node = and(context);
            whitespace();
            if (!end()) throw error("Unexpected '" + source.charAt(position) + "'.");
            return node;
        }

        // Everything defaults to OR before AND: a b|c means a AND (b OR c).
        Node and(Context context) {
            List<Node> nodes = new ArrayList<>();
            nodes.add(or(context));
            while (true) {
                whitespace();
                if (end() || current('>')) break;
                nodes.add(or(context));
            }
            return combine(nodes, true);
        }

        Node or(Context context) {
            List<Node> nodes = new ArrayList<>();
            nodes.add(unary(context));
            while (true) {
                whitespace();
                if (!current('|')) break;
                position++;
                whitespace();
                if (end() || current('|') || current('>')) throw error("OR needs a search term on each side.");
                nodes.add(unary(context));
            }
            return combine(nodes, false);
        }

        Node unary(Context context) {
            if (++depth > 64) throw error("Search grouping or modifiers are nested too deeply.");
            try {
                if (++terms > 1024) throw error("Search is limited to 1024 terms and modifiers.");
                if (end() || current('>') || current('|')) throw error("Expected a search term.");
                if (current('!')) {
                    position++;
                    if (end() || Character.isWhitespace(source.charAt(position))) {
                        throw error("NOT must be followed immediately by a term or group.");
                    }
                    Node child = unary(context);
                    return (entry, budget) -> !child.matches(entry, budget);
                }
                if (current('<')) {
                    position++;
                    whitespace();
                    if (end() || current('>')) throw error("A group needs a search term.");
                    Node group = and(context);
                    if (!current('>')) throw error("Missing closing '>' for group.");
                    position++;
                    return group;
                }
                int start = position;
                String function = function();
                if (function != null) {
                    switch (function) {
                        case "case": return modified(new Context(true, context.wholeWord, context.path, context.regex), function);
                        case "nocase": return modified(new Context(false, context.wholeWord, context.path, context.regex), function);
                        case "path": return modified(new Context(context.matchCase, context.wholeWord, 1, context.regex), function);
                        case "nopath": return modified(new Context(context.matchCase, context.wholeWord, -1, context.regex), function);
                        case "regex": return regex(requiredValue(function, false), context, start, compilation);
                        case "file":
                        case "folder": {
                            boolean directory = function.equals("folder");
                            Node child = valueFollows() ? unary(context) : ALL;
                            return (entry, budget) -> entry.directory == directory && child.matches(entry, budget);
                        }
                        case "ext": return extension(requiredValue(function, false), context, start);
                        case "parent": return parent(requiredValue(function, false), context, start);
                        case "size": {
                            LongTest test = size(requiredValue(function, true), start);
                            return (entry, budget) -> !entry.directory && test.matches(entry.size);
                        }
                        case "dm":
                        case "datemodified":
                        case "date-modified": {
                            LongTest test = date(requiredValue(function, true), start);
                            return (entry, budget) -> test.matches(entry.modified);
                        }
                        case "child": throw new QueryException("child: requires a relationship index and is not supported in this version.", start);
                        default: throw new QueryException("Unsupported search function '" + function + ":'. Quote it to search for literal text.", start);
                    }
                }
                String text = atom(false);
                if (position == start) throw error("Expected a search term.");
                return text(text, context, start, compilation);
            } finally { depth--; }
        }

        Node modified(Context context, String modifier) {
            if (!valueFollows()) throw error(modifier + ": needs a term or <group> immediately after it.");
            return unary(context);
        }

        boolean valueFollows() {
            return !end() && !Character.isWhitespace(source.charAt(position)) && !current('|') && !current('>');
        }

        String function() {
            int scan = position;
            while (scan < source.length()) {
                char c = source.charAt(scan);
                boolean letter = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
                boolean digit = scan > position && c >= '0' && c <= '9';
                if (!letter && !digit && c != '-' && c != '_') break;
                scan++;
            }
            if (scan > position && scan < source.length() && source.charAt(scan) == ':') {
                String name = source.substring(position, scan).toLowerCase(Locale.ROOT);
                // These stable Everything macros insert text, rather than consume an argument.
                if (macroValue(name) != null) return null;
                position = scan + 1;
                return name;
            }
            return null;
        }

        String requiredValue(String function, boolean comparison) {
            int start = position;
            String result = atom(comparison);
            if (position == start || result.isEmpty()) throw error(function + ": needs a value.");
            return result;
        }

        String atom(boolean comparison) {
            StringBuilder value = new StringBuilder();
            boolean quoted = false;
            int quoteStart = -1;
            if (comparison && !end()) {
                char first = source.charAt(position);
                if (first == '<' || first == '>' || first == '=' || first == '!') {
                    value.append(first);
                    position++;
                    if (current('=')) { value.append('='); position++; }
                }
            }
            while (!end()) {
                char c = source.charAt(position);
                if (c == '"') {
                    quoted = !quoted;
                    if (quoted) quoteStart = position;
                    position++;
                } else if (!quoted && (Character.isWhitespace(c) || c == '<' || c == '>' || c == '|' || c == '!')) {
                    break;
                } else if (c == '&') {
                    String entity = entity();
                    if (entity != null) value.append(entity);
                    else { value.append(c); position++; }
                } else {
                    // Backslash is literal, including before quotes. Use &quot: for a literal quote.
                    String macro = quoted ? null : literalMacro();
                    if (macro != null) value.append(macro);
                    else { value.append(c); position++; }
                }
            }
            if (quoted) throw new QueryException("Missing closing double quote.", quoteStart);
            return value.toString();
        }

        String literalMacro() {
            int colon = source.indexOf(':', position);
            if (colon < 0 || colon - position > 4) return null;
            String value = macroValue(source.substring(position, colon).toLowerCase(Locale.ROOT));
            if (value != null) position = colon + 1;
            return value;
        }

        String entity() {
            int colon = source.indexOf(':', position + 1);
            if (colon < 0 || colon - position > 14) return null;
            String name = source.substring(position + 1, colon);
            String result;
            switch (name) {
                case "sp": result = " "; break;
                case "vert": result = "|"; break;
                case "excl": result = "!"; break;
                case "lt": result = "<"; break;
                case "gt": result = ">"; break;
                case "quot": result = "\""; break;
                default:
                    if (!name.startsWith("#")) return null;
                    try {
                        boolean hex = name.startsWith("#x");
                        int cp = Integer.parseInt(name.substring(hex ? 2 : 1), hex ? 16 : 10);
                        if (!Character.isValidCodePoint(cp) || cp >= 0xd800 && cp <= 0xdfff || cp == 0) throw new NumberFormatException();
                        result = new String(Character.toChars(cp));
                    } catch (IllegalArgumentException badEntity) {
                        throw error("Invalid Unicode character entity.");
                    }
            }
            position = colon + 1;
            return result;
        }

        LongTest date(String expression, int start) {
            String op = operator(expression);
            String value = expression.substring(op.length());
            int range = value.indexOf("..");
            if (range >= 0) {
                if (!op.isEmpty()) throw new QueryException("A date range cannot also have a comparison operator.", start);
                DateSpan lower = dateSpan(value.substring(0, range), start);
                DateSpan upper = dateSpan(value.substring(range + 2), start);
                if (lower.start >= upper.end) throw new QueryException("Date range starts after it ends.", start);
                return actual -> actual >= 0 && actual >= lower.start && actual < upper.end;
            }
            DateSpan span = dateSpan(value, start);
            switch (op) {
                case "": case "=": case "==": return actual -> actual >= 0 && actual >= span.start && actual < span.end;
                case "!": case "!=": return actual -> actual >= 0 && (actual < span.start || actual >= span.end);
                case "<": return actual -> actual >= 0 && actual < span.start;
                case "<=": return actual -> actual >= 0 && actual < span.end;
                case ">": return actual -> actual >= 0 && actual >= span.end;
                case ">=": return actual -> actual >= 0 && actual >= span.start;
                default: throw new QueryException("Invalid date comparison.", start);
            }
        }

        DateSpan dateSpan(String text, int position) {
            try {
                String lower = text.toLowerCase(Locale.ROOT);
                LocalDate first;
                LocalDate after;
                if (lower.equals("today") || lower.equals("yesterday")) {
                    first = lower.equals("today") ? today : today.minusDays(1);
                    after = first.plusDays(1);
                } else if (text.matches("[0-9]{4}")) {
                    first = LocalDate.of(Integer.parseInt(text), 1, 1);
                    after = first.plusYears(1);
                } else if (text.matches("[0-9]{4}-[0-9]{2}")) {
                    first = LocalDate.of(Integer.parseInt(text.substring(0, 4)), Integer.parseInt(text.substring(5, 7)), 1);
                    after = first.plusMonths(1);
                } else if (text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                    first = LocalDate.parse(text);
                    after = first.plusDays(1);
                } else throw new DateTimeException("unsupported format");
                return new DateSpan(first.atStartOfDay(zone).toInstant().toEpochMilli(), after.atStartOfDay(zone).toInstant().toEpochMilli());
            } catch (DateTimeException | ArithmeticException badDate) {
                throw new QueryException("Invalid or unsupported date '" + text + "'. Use YYYY, YYYY-MM, YYYY-MM-DD, today or yesterday.", position);
            }
        }

        void whitespace() { while (!end() && Character.isWhitespace(source.charAt(position))) position++; }
        boolean current(char c) { return !end() && source.charAt(position) == c; }
        boolean end() { return position == source.length(); }
        QueryException error(String message) { return new QueryException(message, position); }
    }

    private static final class DateSpan {
        final long start;
        final long end;
        DateSpan(long start, long end) { this.start = start; this.end = end; }
    }

    private static String macroValue(String name) {
        switch (name) {
            case "quot": return "\"";
            case "apos": return "'";
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            default: return null;
        }
    }

    private static Node text(String value, Context context, int position, Compilation compilation) {
        if (context.regex) return regex(value, context, position, compilation);
        final boolean path = context.path > 0 || context.path == 0 && value.indexOf('/') >= 0;
        if (value.indexOf('*') >= 0 || value.indexOf('?') >= 0) {
            Glob pattern = new Glob(value, context.matchCase);
            return (entry, budget) -> {
                budget.pattern(position);
                return pattern.matches(path ? entry.path() : entry.name, budget);
            };
        }
        final String needle = context.matchCase ? value : value.toLowerCase(Locale.ROOT);
        return (entry, budget) -> {
            String haystack = path ? (context.matchCase ? entry.path() : entry.foldedPath()) : (context.matchCase ? entry.name : entry.foldedName);
            if (!context.wholeWord) return haystack.contains(needle);
            if (needle.isEmpty()) return true;
            int at = haystack.indexOf(needle);
            while (at >= 0) {
                budget.spend(1);
                int end = at + needle.length();
                if ((at == 0 || !word(haystack.codePointBefore(at))) && (end == haystack.length() || !word(haystack.codePointAt(end)))) return true;
                at = haystack.indexOf(needle, at + 1);
            }
            return false;
        };
    }

    private static boolean word(int cp) { return Character.isLetterOrDigit(cp) || cp == '_'; }

    private static Node regex(String value, Context context, int position, Compilation compilation) {
        try {
            RegexLimits.check(value, position);
            Pattern pattern = Pattern.compile(value, context.matchCase ? 0 : Pattern.CASE_INSENSITIVE);
            int size = pattern.programSize();
            compilation.add(size, position);
            return (entry, budget) -> {
                budget.pattern(position);
                try {
                    return pattern.matcher(new PatternBudget.Input(context.path > 0 ? entry.path() : entry.name, budget, size)).find();
                } catch (StackOverflowError complex) {
                    throw RegexLimits.complex(position);
                }
            };
        } catch (PatternSyntaxException badRegex) {
            throw new QueryException("Invalid regular expression: " + badRegex.getDescription()
                    + ". This search uses RE2 syntax; backreferences and lookaround are unsupported. Use ordinary groups or literal text.", position);
        } catch (StackOverflowError complex) {
            throw RegexLimits.complex(position);
        }
    }

    /** Greedy wildcard matching, with bounded retries at the most recent star. */
    private static final class Glob {
        final int[] tokens;
        final boolean matchCase;

        Glob(String value, boolean matchCase) {
            this.matchCase = matchCase;
            int[] codePoints = value.codePoints().toArray();
            int length = 0;
            for (int cp : codePoints) {
                if (cp != '*' || length == 0 || codePoints[length - 1] != '*') codePoints[length++] = cp;
            }
            tokens = java.util.Arrays.copyOf(codePoints, length);
        }

        boolean matches(String input, PatternBudget budget) {
            int at = 0, token = 0, star = -1, retry = 0;
            while (at < input.length()) {
                budget.spend(1);
                if (token < tokens.length && tokens[token] == '*') {
                    star = token++;
                    retry = at;
                } else if (token < tokens.length && (tokens[token] == '?' || same(tokens[token], input.codePointAt(at)))) {
                    at += Character.charCount(input.codePointAt(at));
                    token++;
                } else if (star >= 0 && retry < input.length()) {
                    retry += Character.charCount(input.codePointAt(retry));
                    at = retry;
                    token = star + 1;
                } else return false;
            }
            while (token < tokens.length && tokens[token] == '*') {
                budget.spend(1);
                token++;
            }
            return token == tokens.length;
        }

        boolean same(int a, int b) {
            if (a == b) return true;
            if (matchCase) return false;
            int upperA = Character.toUpperCase(a), upperB = Character.toUpperCase(b);
            return upperA == upperB || Character.toLowerCase(upperA) == Character.toLowerCase(upperB);
        }
    }

    private static Node extension(String value, Context context, int position) {
        String[] extensions = value.split(";", -1);
        List<Glob> patterns = new ArrayList<>();
        for (String ext : extensions) {
            if (ext.startsWith(".")) ext = ext.substring(1);
            if (ext.isEmpty() || ext.indexOf('/') >= 0) throw new QueryException("ext: needs extensions separated by semicolons, for example ext:jpg;png.", position);
            patterns.add(new Glob(ext, context.matchCase));
        }
        return (entry, budget) -> {
            if (entry.directory) return false;
            budget.pattern(position);
            int dot = entry.name.lastIndexOf('.');
            String ext = dot < 0 ? "" : entry.name.substring(dot + 1);
            for (Glob pattern : patterns) if (pattern.matches(ext, budget)) return true;
            return false;
        };
    }

    private static Node parent(String value, Context context, int position) {
        if (!value.startsWith("/")) throw new QueryException("parent: needs an absolute Android folder path beginning with '/'.", position);
        while (value.length() > 1 && value.endsWith("/")) value = value.substring(0, value.length() - 1);
        final String expected = context.matchCase ? value : value.toLowerCase(Locale.ROOT);
        return (entry, budget) -> (context.matchCase ? entry.parent : entry.parent.toLowerCase(Locale.ROOT)).equals(expected);
    }

    private static String operator(String value) {
        if (value.startsWith("<=") || value.startsWith(">=") || value.startsWith("!=") || value.startsWith("==")) return value.substring(0, 2);
        if (!value.isEmpty() && "<>=!".indexOf(value.charAt(0)) >= 0) return value.substring(0, 1);
        return "";
    }

    private static LongTest size(String expression, int position) {
        String op = operator(expression);
        String value = expression.substring(op.length());
        int range = value.indexOf("..");
        if (range < 0) range = value.indexOf('-');
        if (range >= 0) {
            if (!op.isEmpty()) throw new QueryException("A size range cannot also have a comparison operator.", position);
            int skip = value.startsWith("..", range) ? 2 : 1;
            long lower = bytes(value.substring(0, range), position).value;
            long upper = bytes(value.substring(range + skip), position).value;
            if (lower > upper) throw new QueryException("Size range starts after it ends.", position);
            return actual -> actual >= 0 && actual >= lower && actual <= upper;
        }
        Quantity number = bytes(value, position);
        long expected = number.value;
        long upper = expected > Long.MAX_VALUE - (number.granularity - 1) ? Long.MAX_VALUE : expected + number.granularity - 1;
        switch (op) {
            case "": return actual -> actual >= 0 && actual >= expected && actual <= upper;
            case "!": return actual -> actual >= 0 && (actual < expected || actual > upper);
            case "=": case "==": return actual -> actual >= 0 && actual == expected;
            case "!=": return actual -> actual >= 0 && actual != expected;
            case "<": return actual -> actual >= 0 && actual < expected;
            case "<=": return actual -> actual >= 0 && actual <= expected;
            case ">": return actual -> actual >= 0 && actual > expected;
            case ">=": return actual -> actual >= 0 && actual >= expected;
            default: throw new QueryException("Invalid size comparison.", position);
        }
    }

    private static final class Quantity {
        final long value;
        final long granularity;
        Quantity(long value, long granularity) { this.value = value; this.granularity = granularity; }
    }

    private static Quantity bytes(String text, int position) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)([a-zA-Z]*)").matcher(text);
        if (!match.matches()) throw new QueryException("Invalid size '" + text + "'. Use bytes or KB, MB, GB, TB units.", position);
        String unit = match.group(2).toLowerCase(Locale.ROOT);
        int power;
        switch (unit) {
            case "": case "b": power = 0; break;
            case "k": case "kb": case "kib": power = 1; break;
            case "m": case "mb": case "mib": power = 2; break;
            case "g": case "gb": case "gib": power = 3; break;
            case "t": case "tb": case "tib": power = 4; break;
            default: throw new QueryException("Unsupported size unit '" + unit + "'. Use B, KB, MB, GB or TB.", position);
        }
        BigDecimal number = new BigDecimal(match.group(1));
        long unitSize = 1L << (power * 10);
        try {
            long result = number.multiply(BigDecimal.valueOf(unitSize)).setScale(0, RoundingMode.DOWN).longValueExact();
            long granularity = Math.max(1, BigDecimal.valueOf(unitSize).movePointLeft(number.scale()).setScale(0, RoundingMode.DOWN).longValueExact());
            return new Quantity(result, granularity);
        } catch (ArithmeticException tooLarge) {
            throw new QueryException("Size is too large.", position);
        }
    }
}
