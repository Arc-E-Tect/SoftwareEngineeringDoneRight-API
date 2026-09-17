/**
 * The contract model: what a bundled OpenAPI 3 contract says, in the terms code
 * generation needs, and nothing that knows about Java, REST Docs or WireMock.
 *
 * <p>{@link com.arc_e_tect.gradle.apionly.transcriberj.model.ContractParser} builds a
 * {@link com.arc_e_tect.gradle.apionly.transcriberj.model.ContractModel} from a
 * contract the API-Only Subscriber fetched. The model keeps everything the
 * contract says about its components and operations: what it types, it types;
 * what it does not, it keeps as raw values and reports as a
 * {@link com.arc_e_tect.gradle.apionly.transcriberj.model.Finding}, so nothing is
 * dropped without a trace.
 *
 * <p>Values are immutable. An absent keyword is {@code null}; an empty one is empty.
 */
package com.arc_e_tect.gradle.apionly.transcriberj.model;
