package com.arc_e_tect.gradle.apionly.transcriberj.spi;

import java.util.Arrays;
import java.util.List;

/**
 * What a contract case tests: which response the contract declares for its request, and so
 * what a target must do before the request is sent.
 */
public enum CaseKind {

    /**
     * A valid request, expecting an exact {@code 2xx} the operation declares. Requires state:
     * the resource exists for a read, an update or a delete, and does not for a create.
     */
    SUCCESS("success", true),

    /**
     * A valid request naming a resource that does not exist, expecting the declared {@code 404}.
     * Requires state: the resource the path names must be absent.
     */
    NOT_FOUND("notFound", true),

    /** A valid request whose {@code Accept} no declared response offers, expecting the declared {@code 406}. */
    NOT_ACCEPTABLE("notAcceptable", false),

    /** The valid body sent with a content type the operation does not accept, expecting the declared {@code 415}. */
    UNSUPPORTED_MEDIA_TYPE("unsupportedMediaType", false),

    /** A request with exactly one constraint violated, expecting the configured invalid-request status. */
    INVALID_REQUEST("invalidRequest", false);

    private final String setting;
    private final boolean requiresState;

    CaseKind(String setting, boolean requiresState) {
        this.setting = setting;
        this.requiresState = requiresState;
    }

    /**
     * How a project names this kind in the {@code derive} setting, such as {@code notFound}.
     *
     * @return the name
     */
    public String setting() {
        return setting;
    }

    /**
     * Whether a case of this kind can only pass when the target is in a particular state, which
     * a fixture must arrange before the request is sent.
     *
     * @return whether it requires state
     */
    public boolean requiresState() {
        return requiresState;
    }

    /**
     * Every kind's setting name, in canonical order: the default of the {@code derive} setting.
     *
     * @return the names
     */
    public static List<String> settings() {
        return Arrays.stream(values()).map(CaseKind::setting).toList();
    }

    /**
     * The kind a setting name names.
     *
     * @param setting the name, such as {@code notFound}
     * @return the kind
     * @throws IllegalArgumentException when no kind has that name
     */
    public static CaseKind ofSetting(String setting) {
        return Arrays.stream(values()).filter(k -> k.setting.equals(setting)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("derive names '" + setting + "', which is not a kind; "
                        + "the kinds are " + String.join(", ", settings())));
    }
}
