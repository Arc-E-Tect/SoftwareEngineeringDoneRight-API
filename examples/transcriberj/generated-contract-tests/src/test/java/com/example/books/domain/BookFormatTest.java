package com.example.books.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests of {@link BookFormat}.
 */
class BookFormatTest {

    @ParameterizedTest
    @EnumSource(BookFormat.class)
    void eachFormatIsFoundByItsCode(BookFormat format) {
        assertThat(BookFormat.fromCode(format.code())).isSameAs(format);
    }

    @Test
    void theCodeIsTheFormatsNameInLowerCase() {
        assertThat(BookFormat.PAPERBACK.code()).isEqualTo("paperback");
    }

    @Test
    void anUnknownCodeIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> BookFormat.fromCode("audiobook"))
                .withMessageContaining("audiobook");
    }
}
