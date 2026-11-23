package io.github.dsheirer.build

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

abstract class PrepareMacosUsbNatives extends DefaultTask {
    @InputFile File scriptFile
    @InputFile abstract RegularFileProperty getNativeJar()
    @Optional @InputDirectory abstract DirectoryProperty getPrebuiltDirectory()
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()
    @Inject protected abstract ExecOperations getExecOperations()

    @TaskAction
    void prepare() {
        List<String> arguments = ['python3', scriptFile.absolutePath,
                '--output', outputDirectory.get().asFile.absolutePath,
                '--native-jar', nativeJar.get().asFile.absolutePath]
        if(prebuiltDirectory.isPresent()) {
            arguments += ['--prebuilt', prebuiltDirectory.get().asFile.absolutePath]
        }
        execOperations.exec { spec -> spec.commandLine(arguments) }
    }
}
