package io.github.dsheirer.build

import java.nio.file.Files
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class MaterializeRuntimeLegalLinks extends DefaultTask {
    @InputFile File archiveFile
    @Internal FileTree archiveContents
    @Input String archiveRoot
    @Input String archiveName
    @Input Map<String,String> legalLinkSources
    @Input int expectedLegalLinks
    @OutputDirectory File outputDirectory

    @TaskAction
    void materialize() {
        if(outputDirectory.exists()) {
            Files.walk(outputDirectory.toPath()).withCloseable { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
        Map<String,byte[]> sourceContents = [:]
        List<String> linkPaths = []
        archiveContents.visit { details ->
            if(details.directory) return
            String[] segments = details.relativePath.segments
            if(segments.length < 2 || segments[0] != archiveRoot) {
                throw new GradleException("Unexpected top-level entry in ${archiveName}: ${details.path}")
            }
            String relativePath = segments.drop(1).join('/')
            if(legalLinkSources.values().contains(relativePath)) {
                sourceContents[relativePath] = Files.readAllBytes(details.file.toPath())
            }
            if(relativePath.startsWith('legal/') && details.file.length() == 0) linkPaths.add(relativePath)
        }
        if(linkPaths.size() != expectedLegalLinks) {
            throw new GradleException("Expected ${expectedLegalLinks} vendor legal links in ${archiveName}, found ${linkPaths.size()}")
        }
        linkPaths.each { linkPath ->
            String sourcePath = legalLinkSources[linkPath.tokenize('/').last()]
            byte[] contents = sourceContents[sourcePath]
            if(sourcePath == null || contents == null || contents.length == 0) {
                throw new GradleException("Cannot materialize vendor legal link ${linkPath} in ${archiveName}")
            }
            File destination = new File(outputDirectory, linkPath)
            Files.createDirectories(destination.parentFile.toPath())
            Files.write(destination.toPath(), contents)
        }
    }
}
