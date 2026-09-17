package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaTextTest {

    private static final char BACKSLASH = 92;

    @Test
    void typeNamesKeepAnIdentifiersCasingAndPascalCaseEverythingElse() {
        assertThat(JavaText.typeName("UserV1")).isEqualTo("UserV1");
        assertThat(JavaText.typeName("userV1")).isEqualTo("UserV1");
        assertThat(JavaText.typeName("user-account")).isEqualTo("UserAccount");
        assertThat(JavaText.typeName("class")).isEqualTo("Class");
        assertThat(JavaText.typeName("$ref")).isEqualTo("Ref");
        assertThat(JavaText.typeName("---")).isEqualTo("Unnamed");
        assertThat(JavaText.typeName("404")).isEqualTo("Type404");
    }

    @Test
    void variableNamesAreCamelCaseAndNeverKeywords() {
        assertThat(JavaText.variableName("email_address")).isEqualTo("emailAddress");
        assertThat(JavaText.variableName("EmailAddress")).isEqualTo("emailAddress");
        assertThat(JavaText.variableName("default")).isEqualTo("defaultValue");
        assertThat(JavaText.variableName("_")).isEqualTo("value");
        assertThat(JavaText.variableName("2nd")).isEqualTo("value2nd");
    }

    @Test
    void constantNamesAreUpperSnakeCase() {
        assertThat(JavaText.constantName("emailAddress")).isEqualTo("EMAIL_ADDRESS");
        assertThat(JavaText.constantName("first-name")).isEqualTo("FIRST_NAME");
        assertThat(JavaText.constantName("@")).isEqualTo("VALUE");
        assertThat(JavaText.constantName("1st")).isEqualTo("VALUE1ST");
    }

    @Test
    void literalsEscapeWhatWouldEndThemAndWriteControlsInOctal() {
        String input = "a" + (char) 34 + BACKSLASH + (char) 10 + (char) 13 + (char) 1 + (char) 0x7f + "é";
        String expected = String.valueOf((char) 34) + "a" + BACKSLASH + (char) 34 + BACKSLASH + BACKSLASH
                + BACKSLASH + "n" + BACKSLASH + "r" + BACKSLASH + "001" + BACKSLASH + "177" + "é" + (char) 34;
        assertThat(JavaText.literal(input)).isEqualTo(expected);
    }

    @Test
    void commentsCannotBeEndedOrMisreadByTheirText() {
        assertThat(JavaText.comment("a */ <b> & @c " + BACKSLASH + "u0041"))
                .isEqualTo("a *&#47; &lt;b&gt; &amp; &#64;c &#92;u0041");
    }
}
