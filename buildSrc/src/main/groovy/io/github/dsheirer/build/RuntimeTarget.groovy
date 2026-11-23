package io.github.dsheirer.build

import groovy.transform.Immutable
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal

/** Pinned runtime facts only; task providers and mutable build state never belong in this value. */
@Immutable(knownImmutableClasses = [File])
class RuntimeTarget implements Serializable {
    @Input String id
    @Input String targetName
    @Input String archiveName
    @Input String archiveRoot
    @Input String archiveType
    @Input String url
    @Input long size
    @Input String sha256
    @Input String releaseArchitecture
    @Input String rootLauncher
    @Input boolean windows
    @Internal File archiveFile
    @Input String packageRoot
}
