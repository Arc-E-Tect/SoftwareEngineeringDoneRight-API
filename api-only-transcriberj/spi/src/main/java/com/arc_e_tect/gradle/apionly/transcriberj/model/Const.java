package com.arc_e_tect.gradle.apionly.transcriberj.model;

/**
 * The value of a {@code const} keyword.
 *
 * <p>A wrapper, because {@code const: null} is a constant too: a schema without
 * {@code const} has no {@code Const}, and one with {@code const: null} has a
 * {@code Const} whose value is {@code null}.
 *
 * @param value the constant, as parsed: a string, number, boolean, list, map or {@code null}
 */
public record Const(Object value) {
}
