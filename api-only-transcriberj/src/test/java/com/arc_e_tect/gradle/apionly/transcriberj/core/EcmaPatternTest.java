package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The ECMA-262 subset patterns are read in, matched with, and expanded from. */
class EcmaPatternTest {

    private static String first(String pattern, int min, int max) {
        return Lazy.first(EcmaPattern.compile(pattern).values(min, max)).orElse(null);
    }

    private static List<String> all(String pattern, int min, int max) {
        List<String> out = new java.util.ArrayList<>();
        EcmaPattern.compile(pattern).values(min, max).forEachRemaining(out::add);
        return out;
    }

    @Test
    void aPatternIsFoundAnywhereInTheValueUnlessItIsAnchored() {
        assertThat(EcmaPattern.compile("\\d").find("abc1def")).isTrue();
        assertThat(EcmaPattern.compile("^\\d").find("abc1")).isFalse();
        assertThat(EcmaPattern.compile("\\d$").find("1abc")).isFalse();
        assertThat(EcmaPattern.compile("^\\d+$").find("123")).isTrue();
    }

    @Test
    void dollarAnchorsAtTheVeryEndNotBeforeAFinalLineBreak() {
        // java.util.regex's $ also matches before a final line terminator; ECMA-262's does not.
        assertThat(EcmaPattern.compile("^a$").find("a\n")).isFalse();
    }

    @Test
    void dotMatchesOneCodePointButNoLineTerminator() {
        EcmaPattern dot = EcmaPattern.compile("^.$");
        assertThat(dot.find("\uD83D\uDE00")).isTrue();
        assertThat(dot.find("\n")).isFalse();
        assertThat(dot.find("\u2028")).isFalse();
    }

    @Test
    void theShortestValueIsTheFirst() {
        assertThat(first("^[a-z][a-z0-9_-]*$", 0, 100)).isEqualTo("a");
        assertThat(first("^\\d{3}-\\d{4}$", 0, 100)).isEqualTo("000-0000");
        assertThat(first("^(foo|ba+r)$", 0, 100)).isEqualTo("foo");
    }

    @Test
    void anUnanchoredMatchIsPaddedToTheMinimumLength() {
        assertThat(first("\\d", 5, 10)).isEqualTo("0aaaa");
        assertThat(first("\\d$", 5, 10)).isEqualTo("aaaa0");
    }

    @Test
    void anAnchoredPatternIsGrownFromWithinToTheMinimumLength() {
        assertThat(first("^[A-Z]+$", 4, 4)).isEqualTo("AAAA");
    }

    @Test
    void lengthsAreCountedInCodePoints() {
        String value = first("^\uD83D\uDE00+$", 3, 3);
        assertThat(value).isEqualTo("\uD83D\uDE00".repeat(3));
        assertThat(value.codePointCount(0, value.length())).isEqualTo(3);
        assertThat(first("^\\u{1F600}{2}$", 0, 10)).isEqualTo("\uD83D\uDE00".repeat(2));
        assertThat(first("^\\uD83D\\uDE00$", 0, 10)).isEqualTo("\uD83D\uDE00");
    }

    @Test
    void successiveValuesDifferSoThatUniqueItemsCanBeMet() {
        assertThat(all("^[a-c]$", 0, 10)).containsExactly("a", "b", "c");
        assertThat(all("^(x|y)$", 0, 10)).containsExactly("x", "y");
    }

    @Test
    void anAnchorThatCannotHoldYieldsNothing() {
        assertThat(all("a^b", 0, 10)).isEmpty();
        assertThat(all("a$b", 0, 10)).isEmpty();
    }

    @Test
    void anEmptyClassMatchesNothingAndItsNegationEverything() {
        assertThat(EcmaPattern.compile("[]").shortestMatch()).isEqualTo(-1);
        assertThat(EcmaPattern.compile("[]").find("anything")).isFalse();
        assertThat(first("^[^]$", 0, 5)).isEqualTo("a");
    }

    @Test
    void theShortestMatchIsKnownWithoutExpandingAnything() {
        assertThat(EcmaPattern.compile("\\d{3}").shortestMatch()).isEqualTo(3);
        assertThat(EcmaPattern.compile("a*").shortestMatch()).isZero();
        assertThat(EcmaPattern.compile("^(ab|c){2,}$").shortestMatch()).isEqualTo(2);
    }

    @Test
    void aLongQuantifierKeepsItsMeaningWhenMatching() {
        EcmaPattern pattern = EcmaPattern.compile("^a{5000}$");
        assertThat(pattern.find("a".repeat(5000))).isTrue();
        assertThat(pattern.find("a".repeat(4999))).isFalse();
        assertThat(pattern.shortestMatch()).isEqualTo(-1);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
        "[\\d-x]|5|-|x|a",
        "[a\\-z]|-|a|z|b",
        "\\W|-|!|@|a",
        "\\S\\D|a-|b.|c_|a1",
        "a{2}b{1,}c{0,1}?|aab|aabbc|aabbbc|abc",
        "x{,3}|x{,3}|x{,3}|x{,3}|x",
        "a}|a}|a}|a}|a",
        "(?:ab)+|ab|abab|xab|a",
        "(?<name>q)|q|q|q|r",
        "\\/\\.|/.|/.|/.|/a",
    })
    void theSyntaxContractsUseIsRead(String pattern, String match1, String match2, String match3, String miss) {
        EcmaPattern p = EcmaPattern.compile(pattern);
        assertThat(List.of(match1, match2, match3)).allMatch(p::find);
        assertThat(p.find(miss)).isFalse();
        assertThat(Lazy.first(p.values(0, 20))).get().satisfies(v -> assertThat(p.find(v)).isTrue());
    }

    @Test
    void controlCharacterEscapesAreRead() {
        assertThat(EcmaPattern.compile("\\x41\\u0042\\cJ").find("AB\n")).isTrue();
        assertThat(EcmaPattern.compile("^\\f\\v\\t\\r\\0$").find("\f\u000b\t\r\u0000")).isTrue();
        assertThat(EcmaPattern.compile("^[\\b]$").find("\b")).isTrue();
        assertThat(EcmaPattern.compile("[^\\s]").find("\t \u00a0\u3000")).isFalse();
        assertThat(EcmaPattern.compile("^\\s$").find("\ufeff")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"(?=a)", "(?!a)", "(?<=a)", "(?<!a)", "\\ba", "a\\B", "(a)\\1", "\\k<n>",
        "\\p{L}", "\\P{L}", "\\01", "[\\1]"})
    void aConstructThatCannotBeExpandedIsRefusedByName(String pattern) {
        assertThatThrownBy(() -> EcmaPattern.compile(pattern)).isInstanceOf(EcmaPattern.Unsupported.class)
                .hasMessageContaining("which this generator cannot expand");
    }

    @ParameterizedTest
    @ValueSource(strings = {"*a", "a**", "(a", "a)", "[a", "[z-a]", "a{3,1}", "\\", "\\x4", "\\u{}", "\\u{110000}",
        "\\u{zz}", "(?x)", "(?<n", "\\c1", "{2}"})
    void anInvalidPatternIsRefusedAsInvalid(String pattern) {
        assertThatThrownBy(() -> EcmaPattern.compile(pattern)).isInstanceOf(EcmaPattern.Unsupported.class)
                .hasMessageContaining("is not a valid ECMA-262 regular expression");
    }

    @Test
    void aSurrogateEscapeWithoutItsPartnerStaysOneCodeUnit() {
        assertThat(EcmaPattern.compile("\\uD83Dx").find("\uD83Dx")).isTrue();
        assertThat(EcmaPattern.compile("\\uD83D\\u0041").find("\uD83DA")).isTrue();
    }

    @Test
    void aClassOfOnlyUnusualCharactersStillYieldsOne() {
        assertThat(first("^[\u00e9-\u00ea]$", 0, 2)).isEqualTo("\u00e9");
        assertThat(first("^[\\x00-\\x1f]$", 0, 2)).isEqualTo("\u0000");
    }
}
