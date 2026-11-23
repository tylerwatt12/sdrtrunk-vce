package io.github.dsheirer.build

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class DownloadVerifiedRuntime extends DefaultTask {
    @Input String url
    @Input long size
    @Input String sha256
    @OutputFile File archiveFile

    DownloadVerifiedRuntime() {
        outputs.upToDateWhen(new PinnedRuntimeMatches())
    }

    private static class PinnedRuntimeMatches implements Spec<Task>, Serializable {
        @Override
        boolean isSatisfiedBy(Task candidate) {
            DownloadVerifiedRuntime task = (DownloadVerifiedRuntime)candidate
            RuntimeSupport.matches(task.archiveFile, task.size, task.sha256)
        }
    }

    @TaskAction
    void download() {
        if(RuntimeSupport.matches(archiveFile, size, sha256)) return
        File destination = archiveFile
        destination.parentFile.mkdirs()
        File partial = new File(destination.parentFile,
                "${destination.name}.part-${ProcessHandle.current().pid()}-${Thread.currentThread().id}")
        try {
            Files.deleteIfExists(partial.toPath())
            def connection = new URI(url).toURL().openConnection()
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.setRequestProperty('User-Agent', 'sdrtrunk-vce Gradle packager')
            connection.getInputStream().withCloseable { input ->
                partial.withOutputStream { output -> input.transferTo(output) }
            }
            if(!RuntimeSupport.matches(partial, size, sha256)) {
                throw new GradleException("Downloaded runtime failed size or checksum verification: ${url}")
            }
            try {
                Files.move(partial.toPath(), destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            catch(java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            logger.lifecycle("Cached ${destination.name} in ${destination.parentFile}")
        }
        finally {
            Files.deleteIfExists(partial.toPath())
        }
    }
}
