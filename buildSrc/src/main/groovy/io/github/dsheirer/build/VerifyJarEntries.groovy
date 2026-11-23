package io.github.dsheirer.build

import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

abstract class VerifyJarEntries extends DefaultTask {
    @InputFile abstract RegularFileProperty getArchive()
    @Input List<String> requiredEntries = []
    @Input List<String> retiredEntries = []
    @Input List<String> retiredPrefixes = []
    @Input String componentName

    @TaskAction
    void verify() {
        File jarFile = archive.get().asFile
        new ZipFile(jarFile).withCloseable { zip ->
            Set<String> entries = zip.entries().toList()*.name as Set
            Set<String> missing = (requiredEntries as Set) - entries
            if(!missing.isEmpty()) {
                throw new GradleException("${componentName} is missing from ${jarFile.name}: ${missing}")
            }
            Set<String> retired = entries.findAll { entry ->
                retiredEntries.contains(entry) || retiredPrefixes.any { entry.startsWith(it) }
            } as Set
            if(!retired.isEmpty()) {
                throw new GradleException("Retired ${componentName} entries remain in ${jarFile.name}: ${retired}")
            }
        }
    }
}
