package io.github.dsheirer.build

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.bundling.Zip

abstract class VerifiedRuntimeZip extends Zip {
    @Nested RuntimeTarget target
    @Input String applicationName
    @Input String releaseVersion
    @Input String updateTrack
    @Input String updateBuild
    @Input String libericaJavaVersion
    @Input String libericaRuntimeVersion
    @Input List<String> requiredVoiceEntries
    @Input List<String> retiredVoiceEntries
    @Input List<String> retiredVoicePrefixes
    @Input abstract Property<String> getExpectedManifestClassPath()

    VerifiedRuntimeZip() { doLast(new VerifyArchive()) }

    private static class VerifyArchive implements Action<Task>, Serializable {
        @Override
        void execute(Task candidate) {
            VerifiedRuntimeZip task = (VerifiedRuntimeZip)candidate
            RuntimePackageVerifier.verify(task.target, task.archiveFile.get().asFile, [
                    applicationName: task.applicationName, releaseVersion: task.releaseVersion,
                    updateTrack: task.updateTrack, updateBuild: task.updateBuild,
                    libericaJavaVersion: task.libericaJavaVersion, libericaRuntimeVersion: task.libericaRuntimeVersion,
                    expectedManifestClassPath: task.expectedManifestClassPath.get(),
                    requiredVoiceEntries: task.requiredVoiceEntries, retiredVoiceEntries: task.retiredVoiceEntries,
                    retiredVoicePrefixes: task.retiredVoicePrefixes])
            task.logger.lifecycle("Verified ${task.target.id} bundled runtime ${task.libericaRuntimeVersion}")
        }
    }
}
