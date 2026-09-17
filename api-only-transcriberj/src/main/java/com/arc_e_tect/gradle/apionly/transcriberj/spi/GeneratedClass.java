package com.arc_e_tect.gradle.apionly.transcriberj.spi;

import com.arc_e_tect.gradle.apionly.transcriberj.model.Provenance;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Schema;

/**
 * One core class, as the core emitter names it.
 *
 * @param simpleName  its simple name, in the base package
 * @param origin      what it was generated from
 * @param key         the component's name for a component, the JSON pointer of the
 *                    schema for an inline schema, or the JSON pointer of the operation
 *                    for an operation
 * @param exposed     whether it is public; a class nothing outside the package needs is
 *                    package-private
 * @param provenance  where it came from; an inline schema has no fragment path
 * @param schema      the schema its body and fields are generated from, or {@code null}
 *                    when it has none, such as a response with several media types
 * @param bodyShaped  whether it has {@code body(...)} and {@code fields(...)}: its schema
 *                    describes an object, rather than a scalar or a list
 */
public record GeneratedClass(
        String simpleName,
        Origin origin,
        String key,
        boolean exposed,
        Provenance provenance,
        Schema schema,
        boolean bodyShaped) {
}
