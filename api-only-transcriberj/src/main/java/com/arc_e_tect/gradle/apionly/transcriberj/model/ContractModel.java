package com.arc_e_tect.gradle.apionly.transcriberj.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A bundled contract, in the terms code generation needs: its OpenAPI document,
 * and its AsyncAPI document where it has one, as one model.
 *
 * @param openapi         {@code openapi}, the specification version
 * @param title           the OpenAPI document's {@code info.title}, or {@code null}
 * @param asyncTitle      the AsyncAPI document's own {@code info.title}, or {@code null}
 *                        when the contract has no AsyncAPI document, or that document
 *                        gives none; Microcks imports an AsyncAPI-described service
 *                        under this title, not {@link #title}, so a tool matching an
 *                        imported service to this contract needs this one instead
 * @param version         {@code info.version}, the contract's own version, or {@code null}
 * @param components      the schema components, in declaration order
 * @param responses       the reusable response components, in declaration order
 * @param parameters      the reusable parameter components, in declaration order
 * @param requestBodies   the reusable request body components, in declaration order
 * @param otherComponents components of every other type -- security schemes, headers,
 *                        examples and the like -- keyed by type, as parsed
 * @param paths           the path items, in declaration order
 * @param channels        the channels of the contract's AsyncAPI document, in
 *                        declaration order; empty when it has none
 * @param asyncOperations the operations of that document, in declaration order
 * @param findings        every classified construct, in the order it was found
 */
public record ContractModel(
        String openapi,
        String title,
        String asyncTitle,
        String version,
        List<Component> components,
        List<Reusable<Response>> responses,
        List<Reusable<Parameter>> parameters,
        List<Reusable<RequestBody>> requestBodies,
        Map<String, Object> otherComponents,
        List<PathItem> paths,
        List<AsyncChannel> channels,
        List<AsyncOperation> asyncOperations,
        List<Finding> findings) {

    /**
     * The schema component with the given name.
     *
     * @param name its key under {@code components.schemas}
     * @return the component, or empty when there is none
     */
    public Optional<Component> component(String name) {
        return components.stream().filter(c -> c.name().equals(name)).findFirst();
    }

    /**
     * Every operation, path by path, in declaration order.
     *
     * @return the operations
     */
    public List<Operation> operations() {
        return paths.stream().flatMap(p -> p.operations().stream()).toList();
    }

    /**
     * The operation with the given {@code operationId}.
     *
     * @param operationId the id
     * @return the operation, or empty when there is none
     */
    /**
     * The messages of every channel, in declaration order.
     *
     * @return the messages
     */
    public List<AsyncMessage> messages() {
        return channels.stream().flatMap(c -> c.messages().stream()).toList();
    }

    /**
     * The operation with the given {@code operationId}.
     *
     * @param operationId the id
     * @return the operation, or empty when there is none
     */
    public Optional<Operation> operation(String operationId) {
        return operations().stream().filter(o -> operationId.equals(o.operationId())).findFirst();
    }
}
