package com.arc_e_tect.gradle.apionly.transcriberj.spi;

/**
 * A library generated code needs.
 *
 * <p>When the project does not declare it, the plugin adds it at {@code pinnedVersion},
 * the version this emitter was tested with. When the project declares it, the
 * project's version wins, and a version at or above {@code untestedFrom} is
 * reported as not tested with this emitter.
 *
 * @param group         the Maven group
 * @param name          the Maven artifact name
 * @param pinnedVersion the version added when the project declares none
 * @param untestedFrom  the lowest version this emitter has not been tested with, such
 *                      as {@code 4}; {@code null} when there is none
 */
public record ManagedDependency(String group, String name, String pinnedVersion, String untestedFrom) {
}
