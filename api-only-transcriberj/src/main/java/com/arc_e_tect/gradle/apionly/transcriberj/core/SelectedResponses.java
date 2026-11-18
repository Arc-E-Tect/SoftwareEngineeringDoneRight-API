package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.Operation;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Parameter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The success responses an operation says a request header selects, in its
 * {@code x-transcriberj-selected-responses}: {@code Prefer: respond-async} selecting {@code 202},
 * with an {@code Idempotency-Key} going with it.
 *
 * <p>{@code with} is a hint: the headers it names are sent with the selecting value, and nothing
 * more. {@code requires} is a rule: the headers it names are sent too, and the service must refuse
 * the selecting value without them, which an invalid-request case for each tests.
 *
 * <p>The marker is the operation's, not the response's: whether a header selects a response is
 * what the operation does with the request, and a response may be shared by operations that do
 * not. A marker that names what the operation does not declare, or that no request could honour,
 * fails the generation, so that it never silently does nothing.
 */
final class SelectedResponses {

    /** The extension, on an operation. */
    static final String EXTENSION = "x-transcriberj-selected-responses";

    private static final List<String> KEYS = List.of("in", "name", "value", "with", "requires");
    private static final Pattern SUCCESS = Pattern.compile("2[0-9][0-9]");

    /**
     * How one response is selected.
     *
     * @param status the status it selects
     * @param in     where the selecting parameter is: {@code header}
     * @param name   the selecting header, as the operation declares it
     * @param value  the value that selects the response
     * @param with     the headers sent with it, as the operation declares them: a hint
     * @param requires the headers the service requires with it, as the operation declares them: sent
     *                 too, and each left out by an invalid-request case
     */
    record Selection(String status, String in, String name, String value, List<String> with, List<String> requires) {
    }

    /** No response selected: an operation without the marker. */
    static final SelectedResponses NONE = new SelectedResponses(Map.of());

    private final Map<String, Selection> byStatus;

    private SelectedResponses(Map<String, Selection> byStatus) {
        this.byStatus = byStatus;
    }

    /** The marker the contract is wrong about: the generation stops. */
    static final class InvalidSelection extends GenerationException {
        private static final long serialVersionUID = 1L;

        InvalidSelection(Operation operation, String what) {
            super(EXTENSION + " at " + CoreClassNames.operationLocation(operation) + " " + what);
        }
    }

    /**
     * The responses an operation's marker selects.
     *
     * @throws InvalidSelection when the marker names what the operation does not declare, or what
     *                          no request could honour
     */
    static SelectedResponses of(Operation operation, ValidRequests requests, ValidValues values) {
        Object raw = operation.other() == null ? null : operation.other().get(EXTENSION);
        if (raw == null) return NONE;
        if (!(raw instanceof Map<?, ?> map)) {
            throw new InvalidSelection(operation, "must map a success status to how it is selected, such as "
                    + "'202': {in: header, name: Prefer, value: respond-async}");
        }
        Set<String> declared = new LinkedHashSet<>();
        if (operation.responses() != null) operation.responses().forEach(r -> declared.add(r.status()));
        Map<String, ValidRequests.Located> headers = new LinkedHashMap<>();
        for (ValidRequests.Located located : requests.parameters(operation)) {
            Parameter p = located.parameter();
            if ("header".equals(p.in()) && p.name() != null) headers.put(p.name().toLowerCase(Locale.ROOT), located);
        }
        Map<String, Selection> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String status = String.valueOf(entry.getKey());
            String at = "selects " + status;
            if (!SUCCESS.matcher(status).matches()) {
                throw new InvalidSelection(operation, at + ", but only an exact 2xx success response can be selected");
            }
            if (!declared.contains(status)) {
                throw new InvalidSelection(operation, at + ", a status the operation does not declare");
            }
            if (!(entry.getValue() instanceof Map<?, ?> how)) {
                throw new InvalidSelection(operation, at + ", but does not say how: {in: header, name: ..., value: ...}");
            }
            for (Object key : how.keySet()) {
                if (!KEYS.contains(String.valueOf(key))) {
                    throw new InvalidSelection(operation, at + " with the key " + key + ", which it does not know; its "
                            + "keys are in, name, value, with and requires");
                }
            }
            String in = text(operation, how, "in", at);
            if (!in.equals("header")) {
                throw new InvalidSelection(operation, at + " by a parameter in " + in + ", but only header parameters can "
                        + "select a response yet");
            }
            String name = text(operation, how, "name", at);
            ValidRequests.Located selecting = header(operation, headers, requests, name, at);
            if (Boolean.TRUE.equals(selecting.parameter().required())) {
                throw new InvalidSelection(operation, at + " by the header " + name + ", which is required, so no "
                        + "request of another response could leave it out");
            }
            String value = text(operation, how, "value", at);
            if (!valid(value, selecting, values)) {
                throw new InvalidSelection(operation, at + " by " + name + ": " + value + ", a value the schema of "
                        + "header parameter " + name + " at " + selecting.location() + "/schema does not allow");
            }
            List<String> with = headers(operation, how, "with", headers, requests, at);
            List<String> requires = headers(operation, how, "requires", headers, requests, at);
            for (String required : requires) {
                if (required.equalsIgnoreCase(selecting.parameter().name())) {
                    throw new InvalidSelection(operation, at + " by " + name + ", which requires " + required
                            + " itself: the header that selects is sent anyway");
                }
                if (with.stream().anyMatch(required::equalsIgnoreCase)) {
                    throw new InvalidSelection(operation, at + " with " + required + " both in with and in requires: "
                            + "requires already sends it, and says the service needs it");
                }
                if (Boolean.TRUE.equals(headers.get(required.toLowerCase(Locale.ROOT)).parameter().required())) {
                    throw new InvalidSelection(operation, at + " and requires " + required + ", but the operation "
                            + "requires it already, whatever selects the response");
                }
            }
            Selection selection = new Selection(status, in, selecting.parameter().name(), value, List.copyOf(with),
                    List.copyOf(requires));
            for (Selection earlier : out.values()) {
                if (earlier.name().equalsIgnoreCase(selection.name()) && earlier.value().equals(selection.value())) {
                    throw new InvalidSelection(operation, "selects " + earlier.status() + " and " + status + " the same "
                            + "way, " + selection.name() + ": " + selection.value() + ", so no request could tell them apart");
                }
            }
            out.put(status, selection);
        }
        return new SelectedResponses(java.util.Collections.unmodifiableMap(out));
    }

    /** The header parameters a list of the marker names, as the operation declares them. */
    private static List<String> headers(Operation operation, Map<?, ?> how, String key,
                                        Map<String, ValidRequests.Located> headers, ValidRequests requests, String at) {
        Object raw = how.get(key);
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new InvalidSelection(operation, at + " with " + raw + ", but " + key + " must list header names");
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) out.add(header(operation, headers, requests, String.valueOf(item), at).parameter().name());
        return out;
    }

    private static String text(Operation operation, Map<?, ?> how, String key, String at) {
        Object value = how.get(key);
        if (!(value instanceof String s) || s.isEmpty()) {
            throw new InvalidSelection(operation, at + " without a " + key + ": {in: header, name: ..., value: ...}");
        }
        return s;
    }

    private static ValidRequests.Located header(Operation operation, Map<String, ValidRequests.Located> headers,
                                                ValidRequests requests, String name, String at) {
        ValidRequests.Located located = headers.get(name.toLowerCase(Locale.ROOT));
        if (located == null) {
            throw new InvalidSelection(operation, at + " by " + name + ", which is no header parameter the operation "
                    + "declares");
        }
        String unsupported = requests.unsupported(located.parameter());
        if (unsupported != null) {
            throw new InvalidSelection(operation, at + " by the header " + name + ", for which no value is generated: "
                    + unsupported);
        }
        return located;
    }

    /** Whether a header's value, which travels as text, is one its schema allows. */
    private static boolean valid(String value, ValidRequests.Located located, ValidValues values) {
        String schemaAt = located.location() + "/schema";
        if (values.valid(value, located.parameter().schema(), schemaAt)) return true;
        if (value.equals("true") || value.equals("false")) {
            return values.valid(Boolean.valueOf(value), located.parameter().schema(), schemaAt);
        }
        try {
            return values.valid(new BigDecimal(value), located.parameter().schema(), schemaAt);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Every selection, in the order the marker gives them. */
    java.util.Collection<Selection> selections() {
        return byStatus.values();
    }

    /** Whether the operation selects no response. */
    boolean isEmpty() {
        return byStatus.isEmpty();
    }

    /** How a response is selected, or null when it is not. */
    Selection selection(String status) {
        return byStatus.get(status);
    }

    /**
     * How a success case of a response treats the selecting headers: a selected response's sends
     * its selecting value and the headers that go with it; every other selecting header is left out.
     */
    ValidRequests.Headers headers(String status) {
        if (byStatus.isEmpty()) return ValidRequests.Headers.NONE;
        Selection selection = byStatus.get(status);
        Map<String, String> fixed = new LinkedHashMap<>();
        Set<String> forced = new LinkedHashSet<>();
        if (selection != null) {
            fixed.put(selection.name().toLowerCase(Locale.ROOT), selection.value());
            selection.with().forEach(w -> forced.add(w.toLowerCase(Locale.ROOT)));
            selection.requires().forEach(r -> forced.add(r.toLowerCase(Locale.ROOT)));
        }
        Set<String> omitted = new LinkedHashSet<>();
        for (Selection s : byStatus.values()) {
            String name = s.name().toLowerCase(Locale.ROOT);
            if (!fixed.containsKey(name) && !forced.contains(name)) omitted.add(name);
        }
        return new ValidRequests.Headers(Map.copyOf(fixed), Set.copyOf(forced), Set.copyOf(omitted));
    }

    /**
     * What a success case's description adds: for a selected response the value that selects it;
     * for another response's request with every parameter, the selecting headers it leaves out.
     */
    String describe(String status, boolean everyParameter) {
        Selection selection = byStatus.get(status);
        if (selection != null) {
            return ", with " + selection.name() + ": " + selection.value() + ", which selects " + status;
        }
        if (byStatus.isEmpty() || !everyParameter) return "";
        List<String> left = byStatus.values().stream()
                .map(s -> s.name() + ", which selects " + s.status()).toList();
        return ", but " + String.join(", and ", left);
    }
}
