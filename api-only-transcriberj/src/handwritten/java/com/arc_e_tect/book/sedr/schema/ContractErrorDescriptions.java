package com.arc_e_tect.book.sedr.schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Reads field-description text from {@code contract-error-descriptions.properties},
 * keyed by status prefix (e.g. {@code bad_request.message}, {@code not_found.details.context}).
 */
final class ContractErrorDescriptions {

    private static final Properties DESCRIPTIONS = load();

    private ContractErrorDescriptions() {
    }

    static String get(String key) {
        String value = DESCRIPTIONS.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "No contract-error-descriptions.properties entry for key '" + key + "'");
        }
        return value;
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream in = ContractErrorDescriptions.class.getClassLoader()
                .getResourceAsStream("contract-error-descriptions.properties")) {
            if (in == null) {
                throw new IllegalStateException("contract-error-descriptions.properties not found on the classpath");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }
}
