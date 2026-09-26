package com.arc_e_tect.gradle.apionly.transcriberj.core;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A JSON Schema {@code pattern}: an ECMA-262 regular expression, for the subset contracts
 * use, read the way JSON Schema 2020-12 reads it.
 *
 * <p>Two things are asked of it: whether a string matches, and which strings do. A string
 * matches when the pattern is <em>found</em> anywhere in it, not only when it matches the
 * whole string; {@code ^} and {@code $} anchor at the start and end of the string. Patterns
 * are read in Unicode mode, so {@code .} and a class match one code point, and lengths are
 * counted in code points, as JSON Schema counts {@code minLength}.
 *
 * <p>Both answers come from the same syntax tree: matching translates the tree, never the
 * pattern's text, into a {@link java.util.regex.Pattern}, so the small differences between
 * ECMA-262 and Java's syntax never reach the answer. Backreferences, lookarounds, word
 * boundaries and Unicode property escapes cannot be expanded into strings, so a pattern that
 * uses one is {@link Unsupported}.
 */
final class EcmaPattern {

    /** The longest value, in code points, this generator produces or reasons about. */
    static final int MAX_LENGTH = 4096;

    /** How many characters of one class are tried at one position. */
    private static final int CHOICES = 64;

    /** The filler padding a match to a longer value. */
    private static final String FILLER = "a";

    /** A pattern this generator cannot use: not valid ECMA-262, or using a construct it cannot expand. */
    static final class Unsupported extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unsupported(String reason) {
            super(reason, null, false, false);
        }
    }

    private sealed interface Node permits Chars, Seq, Alt, Rep, Anchor {
    }

    /** One code point from a set. */
    private record Chars(CharSet set) implements Node {
    }

    private record Seq(List<Node> items) implements Node {
    }

    private record Alt(List<Node> branches) implements Node {
    }

    /** A repetition; {@code max} is -1 when unbounded. */
    private record Rep(Node node, int min, int max) implements Node {
    }

    /** {@code ^} when {@code start}, otherwise {@code $}. */
    private record Anchor(boolean start) implements Node {
    }

    /**
     * Part of a match: its text, its length in code points, and whether an anchor ties
     * its start to the start of the value or its end to the end.
     */
    private record Piece(String text, int length, boolean start, boolean end) {
    }

    private static final Piece EMPTY = new Piece("", 0, false, false);

    private final String source;
    private final Node root;
    private final Map<Node, BitSet> lengths = new IdentityHashMap<>();
    private final Map<Rep, BitSet> repetitions = new HashMap<>();
    private final Map<List<Node>, BitSet[]> suffixes = new IdentityHashMap<>();
    private Pattern compiled;

    private EcmaPattern(String source, Node root) {
        this.source = source;
        this.root = root;
    }

    /**
     * Reads a pattern.
     *
     * @throws Unsupported when it is not valid ECMA-262, or uses a construct this generator cannot expand
     */
    static EcmaPattern compile(String source) {
        return new EcmaPattern(source, new Parser(source).parse());
    }

    String source() {
        return source;
    }

    /** Whether the pattern is found anywhere in the value. */
    boolean find(String value) {
        if (compiled == null) compiled = Pattern.compile(java(root));
        return compiled.matcher(value).find();
    }

    /** The length of the shortest match, in code points, or -1 when nothing matches. */
    int shortestMatch() {
        return lengths(root).nextSetBit(0);
    }

    /**
     * Values the pattern is found in, of {@code min} to {@code max} code points, in a fixed
     * order: shortest first, and for each length a whole match before a match padded with
     * the filler. Each is checked with {@link #find(String)} before it is returned.
     */
    Iterator<String> values(int min, int max) {
        int from = Math.max(min, 0);
        int to = Math.min(max, MAX_LENGTH);
        if (from > to) return Lazy.empty();
        BitSet core = lengths(root);
        // A match anchored at both ends is the whole value: padding it is never tried.
        boolean whole = anchored(root, true) && anchored(root, false);
        return Lazy.filter(Lazy.flatMap(Lazy.range(from, to), total ->
                Lazy.flatMap(Lazy.filter(Lazy.range(total, whole ? total : 0), core::get), c ->
                        Lazy.flatMap(pieces(root, c), p -> padded(p, total - c)))), this::find);
    }

    /** Whether every match of a node begins with {@code ^} (or, for {@code start} false, ends with {@code $}). */
    private static boolean anchored(Node node, boolean start) {
        return switch (node) {
            case Anchor a -> a.start() == start;
            case Alt a -> a.branches().stream().allMatch(b -> anchored(b, start));
            case Seq s -> !s.items().isEmpty() && anchored(s.items().get(start ? 0 : s.items().size() - 1), start);
            case Chars c -> false;
            case Rep r -> r.min() > 0 && anchored(r.node(), start);
        };
    }

    private static Iterator<String> padded(Piece piece, int pad) {
        if (pad == 0) return Lazy.of(piece.text());
        List<String> out = new ArrayList<>(2);
        if (!piece.end()) out.add(piece.text() + FILLER.repeat(pad));
        if (!piece.start()) out.add(FILLER.repeat(pad) + piece.text());
        return out.iterator();
    }

    // ------------------------------------------------------------- lengths

    private BitSet lengths(Node node) {
        BitSet known = lengths.get(node);
        if (known != null) return known;
        BitSet out = switch (node) {
            case Chars c -> c.set().isEmpty() ? new BitSet() : single(1);
            case Anchor a -> single(0);
            case Seq s -> {
                BitSet acc = single(0);
                for (Node item : s.items()) acc = convolve(acc, lengths(item));
                yield acc;
            }
            case Alt a -> {
                BitSet acc = new BitSet();
                for (Node branch : a.branches()) acc.or(lengths(branch));
                yield acc;
            }
            case Rep r -> repeated(r);
        };
        lengths.put(node, out);
        return out;
    }

    private BitSet repeated(Rep r) {
        BitSet known = repetitions.get(r);
        if (known != null) return known;
        BitSet one = lengths(r.node());
        BitSet acc = single(0);
        for (int i = 0; i < r.min() && !acc.isEmpty(); i++) acc = convolve(acc, one);
        BitSet out = (BitSet) acc.clone();
        BitSet nonEmpty = (BitSet) one.clone();
        nonEmpty.clear(0);
        int extra = r.max() < 0 ? MAX_LENGTH : r.max() - r.min();
        BitSet current = acc;
        for (int i = 0; i < extra && !current.isEmpty(); i++) {
            current = convolve(current, nonEmpty);
            out.or(current);
        }
        repetitions.put(r, out);
        return out;
    }

    private static BitSet single(int length) {
        BitSet out = new BitSet();
        out.set(length);
        return out;
    }

    private static BitSet convolve(BitSet a, BitSet b) {
        BitSet out = new BitSet();
        for (int i = a.nextSetBit(0); i >= 0; i = a.nextSetBit(i + 1)) {
            for (int j = b.nextSetBit(0); j >= 0 && i + j <= MAX_LENGTH; j = b.nextSetBit(j + 1)) {
                out.set(i + j);
            }
        }
        return out;
    }

    // -------------------------------------------------------------- pieces

    private Iterator<Piece> pieces(Node node, int length) {
        if (!lengths(node).get(length)) return Lazy.empty();
        return switch (node) {
            case Chars c -> Lazy.map(Lazy.limit(c.set().choices(), CHOICES),
                    cp -> new Piece(Character.toString(cp), 1, false, false));
            case Anchor a -> Lazy.of(new Piece("", 0, a.start(), !a.start()));
            case Alt a -> Lazy.flatMap(a.branches().iterator(), b -> pieces(b, length));
            case Seq s -> sequence(s.items(), 0, length);
            case Rep r -> repetition(r.node(), r.min(), r.max(), length);
        };
    }

    private Iterator<Piece> sequence(List<Node> items, int index, int length) {
        if (index == items.size()) return length == 0 ? Lazy.of(EMPTY) : Lazy.empty();
        Node head = items.get(index);
        BitSet rest = suffixes(items)[index + 1];
        BitSet own = lengths(head);
        return Lazy.flatMap(lengths(own, 0, length, l -> rest.get(length - l)),
                l -> Lazy.flatMap(pieces(head, l), p -> joined(p, sequence(items, index + 1, length - l))));
    }

    /** For each index, the lengths the items from there to the end can match together. */
    private BitSet[] suffixes(List<Node> items) {
        BitSet[] known = suffixes.get(items);
        if (known != null) return known;
        BitSet[] out = new BitSet[items.size() + 1];
        out[items.size()] = single(0);
        for (int i = items.size() - 1; i >= 0; i--) out[i] = convolve(lengths(items.get(i)), out[i + 1]);
        suffixes.put(items, out);
        return out;
    }

    private Iterator<Piece> repetition(Node node, int min, int max, int length) {
        if (max == 0) return length == 0 ? Lazy.of(EMPTY) : Lazy.empty();
        BitSet own = lengths(node);
        int nextMin = Math.max(min - 1, 0);
        int nextMax = max < 0 ? -1 : max - 1;
        BitSet rest = repeated(new Rep(node, nextMin, nextMax));
        return Lazy.concat(
                () -> min == 0 && length == 0 ? Lazy.of(EMPTY) : Lazy.<Piece>empty(),
                () -> Lazy.flatMap(lengths(own, min > 0 ? 0 : 1, length, l -> rest.get(length - l)),
                        l -> Lazy.flatMap(pieces(node, l),
                                p -> joined(p, repetition(node, nextMin, nextMax, length - l)))));
    }

    /**
     * The lengths in a set from {@code from} to {@code to} that pass, visiting only the
     * lengths in the set: a character matches one, so a long repetition stays linear.
     */
    private static Iterator<Integer> lengths(BitSet set, int from, int to, java.util.function.IntPredicate keep) {
        List<Integer> out = new ArrayList<>();
        for (int l = set.nextSetBit(Math.max(from, 0)); l >= 0 && l <= to; l = set.nextSetBit(l + 1)) {
            if (keep.test(l)) out.add(l);
        }
        return out.iterator();
    }

    private static Iterator<Piece> joined(Piece p, Iterator<Piece> rest) {
        return Lazy.filter(Lazy.map(rest, q -> join(p, q)), Objects::nonNull);
    }

    /** Two pieces one after the other, or null when an anchor inside them cannot hold. */
    private static Piece join(Piece p, Piece q) {
        if (q.start() && p.length() > 0) return null;
        if (p.end() && q.length() > 0) return null;
        return new Piece(p.text() + q.text(), p.length() + q.length(),
                p.start() || (q.start() && p.length() == 0), q.end() || (p.end() && q.length() == 0));
    }

    // ---------------------------------------------------------------- java

    /** The tree as a {@link Pattern}, construct by construct, so that it means what ECMA-262 means. */
    private static String java(Node node) {
        return switch (node) {
            case Chars c -> c.set().java();
            case Anchor a -> a.start() ? "\\A" : "\\z";
            case Seq s -> "(?:" + String.join("", s.items().stream().map(EcmaPattern::java).toList()) + ")";
            case Alt a -> "(?:" + String.join("|", a.branches().stream().map(EcmaPattern::java).toList()) + ")";
            case Rep r -> "(?:" + java(r.node()) + "){" + r.min() + "," + (r.max() < 0 ? "" : r.max()) + "}";
        };
    }

    // ------------------------------------------------------------ char set

    /** A set of code points, as sorted, disjoint, inclusive ranges. */
    static final class CharSet {

        private static final int MAX = 0x10FFFF;

        /** The order a class's characters are tried in: letters, digits, printable ASCII, a space. */
        private static final int[][] PREFERRED = {
            {'a', 'z'}, {'A', 'Z'}, {'0', '9'}, {'!', '/'}, {':', '@'}, {'[', '`'}, {'{', '~'}, {' ', ' '}};

        static final CharSet DIGIT = of('0', '9');
        static final CharSet WORD = of('a', 'z').union(of('A', 'Z')).union(DIGIT).union(of('_', '_'));
        static final CharSet SPACE = of('\t', '\r').union(of(' ', ' ')).union(of(0xA0, 0xA0))
                .union(of(0x1680, 0x1680)).union(of(0x2000, 0x200A)).union(of(0x2028, 0x2029))
                .union(of(0x202F, 0x202F)).union(of(0x205F, 0x205F)).union(of(0x3000, 0x3000))
                .union(of(0xFEFF, 0xFEFF));
        static final CharSet LINE_TERMINATOR = of('\n', '\n').union(of('\r', '\r')).union(of(0x2028, 0x2029));
        static final CharSet ANY = of(0, MAX);

        private final int[] ranges;

        private CharSet(int[] ranges) {
            this.ranges = ranges;
        }

        static CharSet of(int lo, int hi) {
            return new CharSet(new int[]{lo, hi});
        }

        static CharSet none() {
            return new CharSet(new int[0]);
        }

        boolean isEmpty() {
            return ranges.length == 0;
        }

        boolean contains(int cp) {
            for (int i = 0; i < ranges.length; i += 2) {
                if (cp >= ranges[i] && cp <= ranges[i + 1]) return true;
            }
            return false;
        }

        CharSet union(CharSet other) {
            List<int[]> all = new ArrayList<>();
            for (int i = 0; i < ranges.length; i += 2) all.add(new int[]{ranges[i], ranges[i + 1]});
            for (int i = 0; i < other.ranges.length; i += 2) all.add(new int[]{other.ranges[i], other.ranges[i + 1]});
            all.sort((a, b) -> Integer.compare(a[0], b[0]));
            List<int[]> merged = new ArrayList<>();
            for (int[] r : all) {
                if (!merged.isEmpty() && r[0] <= merged.get(merged.size() - 1)[1] + 1) {
                    int[] last = merged.get(merged.size() - 1);
                    last[1] = Math.max(last[1], r[1]);
                } else {
                    merged.add(new int[]{r[0], r[1]});
                }
            }
            int[] out = new int[merged.size() * 2];
            for (int i = 0; i < merged.size(); i++) {
                out[2 * i] = merged.get(i)[0];
                out[2 * i + 1] = merged.get(i)[1];
            }
            return new CharSet(out);
        }

        CharSet complement() {
            List<Integer> out = new ArrayList<>();
            int next = 0;
            for (int i = 0; i < ranges.length; i += 2) {
                if (ranges[i] > next) {
                    out.add(next);
                    out.add(ranges[i] - 1);
                }
                next = ranges[i + 1] + 1;
            }
            if (next <= MAX) {
                out.add(next);
                out.add(MAX);
            }
            return new CharSet(out.stream().mapToInt(Integer::intValue).toArray());
        }

        /** The code points of the set in the order they are tried, surrogates left out. */
        Iterator<Integer> choices() {
            return new Iterator<>() {
                private int preferred = 0;
                private int cp = PREFERRED[0][0] - 1;
                private int range = 0;
                private int inRange = ranges.length == 0 ? 0 : ranges[0] - 1;
                private Integer next = advance();

                private Integer advance() {
                    while (preferred < PREFERRED.length) {
                        cp++;
                        if (cp > PREFERRED[preferred][1]) {
                            preferred++;
                            if (preferred < PREFERRED.length) cp = PREFERRED[preferred][0] - 1;
                            continue;
                        }
                        if (contains(cp)) return cp;
                    }
                    while (range < ranges.length) {
                        inRange++;
                        if (inRange > ranges[range + 1]) {
                            range += 2;
                            if (range < ranges.length) inRange = ranges[range] - 1;
                            continue;
                        }
                        if (inRange >= ' ' && inRange <= '~') {
                            inRange = Math.min('~', ranges[range + 1]);
                            continue;
                        }
                        if (inRange >= 0xD800 && inRange <= 0xDFFF) {
                            inRange = Math.min(0xDFFF, ranges[range + 1]);
                            continue;
                        }
                        return inRange;
                    }
                    return null;
                }

                @Override
                public boolean hasNext() {
                    return next != null;
                }

                @Override
                public Integer next() {
                    if (next == null) throw new java.util.NoSuchElementException();
                    Integer out = next;
                    next = advance();
                    return out;
                }
            };
        }

        String java() {
            if (ranges.length == 0) return "(?!)";
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < ranges.length; i += 2) {
                out.append("\\x{").append(Integer.toHexString(ranges[i])).append('}');
                if (ranges[i + 1] != ranges[i]) {
                    out.append("-\\x{").append(Integer.toHexString(ranges[i + 1])).append('}');
                }
            }
            return out.append(']').toString();
        }
    }

    // -------------------------------------------------------------- parser

    /** A recursive-descent reader of the ECMA-262 pattern grammar, in Unicode mode. */
    private static final class Parser {

        private final String source;
        private final int[] cps;
        private int pos;

        Parser(String source) {
            this.source = source;
            this.cps = source.codePoints().toArray();
        }

        Node parse() {
            Node node = disjunction();
            if (pos < cps.length) throw invalid("unmatched )");
            return node;
        }

        private Node disjunction() {
            List<Node> branches = new ArrayList<>();
            branches.add(alternative());
            while (peek() == '|') {
                pos++;
                branches.add(alternative());
            }
            return branches.size() == 1 ? branches.get(0) : new Alt(List.copyOf(branches));
        }

        private Node alternative() {
            List<Node> items = new ArrayList<>();
            while (pos < cps.length && peek() != '|' && peek() != ')') items.add(term());
            return new Seq(List.copyOf(items));
        }

        private Node term() {
            int c = peek();
            if (c == '^' || c == '$') {
                pos++;
                return new Anchor(c == '^');
            }
            return quantifier(atom());
        }

        private Node atom() {
            int c = next();
            switch (c) {
                case '(' -> {
                    if (peek() == '?') {
                        pos++;
                        int kind = next();
                        if (kind == '=' || kind == '!') throw cannot("a lookahead");
                        if (kind == '<' && (peek() == '=' || peek() == '!')) throw cannot("a lookbehind");
                        if (kind == '<') {
                            while (pos < cps.length && peek() != '>') pos++;
                            if (pos == cps.length) throw invalid("unterminated group name");
                            pos++;
                        } else if (kind != ':') {
                            throw invalid("(?" + Character.toString(kind) + " is not a group");
                        }
                    }
                    Node inner = disjunction();
                    if (pos >= cps.length || next() != ')') throw invalid("unterminated group");
                    return inner;
                }
                case '[' -> {
                    return characterClass();
                }
                case '.' -> {
                    return new Chars(CharSet.LINE_TERMINATOR.complement());
                }
                case '\\' -> {
                    return new Chars(escape(false));
                }
                case '*', '+', '?' -> throw invalid("nothing to repeat");
                case '{' -> {
                    int back = pos;
                    pos--;
                    if (bounds() != null) throw invalid("nothing to repeat");
                    pos = back;
                    return literal('{');
                }
                default -> {
                    return literal(c);
                }
            }
        }

        private Node quantifier(Node atom) {
            if (pos >= cps.length) return atom;
            int c = peek();
            int[] bounds;
            if (c == '*') {
                pos++;
                bounds = new int[]{0, -1};
            } else if (c == '+') {
                pos++;
                bounds = new int[]{1, -1};
            } else if (c == '?') {
                pos++;
                bounds = new int[]{0, 1};
            } else if (c == '{') {
                bounds = bounds();
                if (bounds == null) return atom;
            } else {
                return atom;
            }
            if (peek() == '?') pos++;
            if (bounds[1] >= 0 && bounds[0] > bounds[1]) throw invalid("numbers out of order in {} quantifier");
            return new Rep(atom, bounds[0], bounds[1]);
        }

        /** {@code {n}}, {@code {n,}} or {@code {n,m}} at the position, consumed; or null, consuming nothing. */
        private int[] bounds() {
            int start = pos;
            pos++;
            Integer min = number();
            if (min == null) {
                pos = start;
                return null;
            }
            int max = min;
            if (peek() == ',') {
                pos++;
                Integer m = number();
                max = m == null ? -1 : m;
            }
            if (peek() != '}') {
                pos = start;
                return null;
            }
            pos++;
            return new int[]{min, max};
        }

        private Integer number() {
            int start = pos;
            long value = 0;
            while (pos < cps.length && Character.isDigit(cps[pos]) && cps[pos] < 128) {
                value = Math.min(value * 10 + (cps[pos] - '0'), Integer.MAX_VALUE);
                pos++;
            }
            return pos == start ? null : (int) value;
        }

        private Node characterClass() {
            boolean negated = peek() == '^';
            if (negated) pos++;
            CharSet set = CharSet.none();
            while (true) {
                if (pos >= cps.length) throw invalid("unterminated character class");
                if (peek() == ']') {
                    pos++;
                    break;
                }
                Object a = classAtom();
                if (peek() == '-' && pos + 1 < cps.length && cps[pos + 1] != ']') {
                    pos++;
                    Object b = classAtom();
                    if (a instanceof Integer lo && b instanceof Integer hi) {
                        if (lo > hi) throw invalid("range out of order in character class");
                        set = set.union(CharSet.of(lo, hi));
                    } else {
                        set = set.union(asSet(a)).union(CharSet.of('-', '-')).union(asSet(b));
                    }
                } else {
                    set = set.union(asSet(a));
                }
            }
            return new Chars(negated ? set.complement() : set);
        }

        /** One code point, as an Integer, or a class escape, as a CharSet. */
        private Object classAtom() {
            int c = next();
            if (c != '\\') return c;
            if (peek() == 'b') {
                pos++;
                return 8;
            }
            if (peek() == '-') {
                pos++;
                return (int) '-';
            }
            CharSet set = escape(true);
            return set.ranges.length == 2 && set.ranges[0] == set.ranges[1] ? (Object) set.ranges[0] : set;
        }

        private static CharSet asSet(Object atom) {
            return atom instanceof Integer cp ? CharSet.of(cp, cp) : (CharSet) atom;
        }

        /** The escape after a backslash, as the set it stands for. */
        private CharSet escape(boolean inClass) {
            if (pos >= cps.length) throw invalid("\\ at end of pattern");
            int c = next();
            return switch (c) {
                case 'd' -> CharSet.DIGIT;
                case 'D' -> CharSet.DIGIT.complement();
                case 'w' -> CharSet.WORD;
                case 'W' -> CharSet.WORD.complement();
                case 's' -> CharSet.SPACE;
                case 'S' -> CharSet.SPACE.complement();
                case 'b', 'B' -> throw cannot("a word boundary");
                case 'f' -> one(0x0C);
                case 'n' -> one(0x0A);
                case 'r' -> one(0x0D);
                case 't' -> one(0x09);
                case 'v' -> one(0x0B);
                case 'c' -> {
                    int letter = next();
                    if (!(letter >= 'a' && letter <= 'z' || letter >= 'A' && letter <= 'Z')) {
                        throw invalid("\\c must be followed by a letter");
                    }
                    yield one(letter % 32);
                }
                case 'x' -> one(hex(2));
                case 'u' -> one(unicode());
                case '0' -> {
                    if (pos < cps.length && Character.isDigit(peek())) throw cannot("an octal escape");
                    yield one(0);
                }
                case '1', '2', '3', '4', '5', '6', '7', '8', '9' ->
                        throw cannot(inClass ? "an octal escape" : "a backreference");
                case 'k' -> throw cannot("a named backreference");
                case 'p', 'P' -> throw cannot("a Unicode property escape");
                default -> one(c);
            };
        }

        private int unicode() {
            if (peek() == '{') {
                pos++;
                int start = pos;
                while (pos < cps.length && peek() != '}') pos++;
                if (pos == cps.length || pos == start) throw invalid("malformed \\u{...} escape");
                int value;
                try {
                    value = Integer.parseInt(new String(cps, start, pos - start), 16);
                } catch (NumberFormatException e) {
                    throw invalid("malformed \\u{...} escape");
                }
                pos++;
                if (value > CharSet.MAX) throw invalid("\\u{...} beyond the last code point");
                return value;
            }
            int high = hex(4);
            if (Character.isHighSurrogate((char) high) && pos + 1 < cps.length && cps[pos] == '\\'
                    && cps[pos + 1] == 'u') {
                int back = pos;
                pos += 2;
                int low = hex(4);
                if (Character.isLowSurrogate((char) low)) return Character.toCodePoint((char) high, (char) low);
                pos = back;
            }
            return high;
        }

        private int hex(int digits) {
            if (pos + digits > cps.length) throw invalid("malformed hexadecimal escape");
            int value = 0;
            for (int i = 0; i < digits; i++) {
                int d = Character.digit(cps[pos++], 16);
                if (d < 0) throw invalid("malformed hexadecimal escape");
                value = value * 16 + d;
            }
            return value;
        }

        private static CharSet one(int cp) {
            return CharSet.of(cp, cp);
        }

        private static Node literal(int cp) {
            return new Chars(CharSet.of(cp, cp));
        }

        private int peek() {
            return pos < cps.length ? cps[pos] : -1;
        }

        private int next() {
            if (pos >= cps.length) throw invalid("unexpected end of pattern");
            return cps[pos++];
        }

        private Unsupported invalid(String what) {
            return new Unsupported("pattern " + source + " is not a valid ECMA-262 regular expression: " + what);
        }

        private Unsupported cannot(String what) {
            return new Unsupported("pattern " + source + " uses " + what + ", which this generator cannot expand "
                    + "into a value");
        }
    }
}
