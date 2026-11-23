package io.github.dsheirer.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

abstract class VerifyRuntimeArchiveSet extends DefaultTask {
    @Input List<String> expectedArchives
    @InputFiles abstract ConfigurableFileCollection getArchives()
    @Internal abstract DirectoryProperty getImageDirectory()

    @TaskAction
    void verify() {
        Set<String> expected = expectedArchives as Set
        Set<String> actual = archives.files.collect { it.name } as Set
        if(actual != expected) {
            throw new GradleException("Expected exactly six release archives. Expected ${expected}; found ${actual}")
        }
        logger.lifecycle("Created and verified all six release archives in ${imageDirectory.get()}")
    }
}
