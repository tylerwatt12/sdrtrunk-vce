package io.github.dsheirer.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

abstract class VerifyReleaseNotes extends DefaultTask {
    @Input String releaseVersion
    @Input boolean requireApproval
    @Optional @InputFile File metadataFile
    @Optional @InputFile File htmlFile

    static boolean numbered(String version) {
        String normalized = version.toLowerCase(Locale.US)
        !normalized.contains('nightly') && !normalized.contains('snapshot') && !normalized.contains('dev')
    }

    @TaskAction
    void verify() {
        if(!numbered(releaseVersion)) return
        if(metadataFile == null || !metadataFile.isFile() || !requireApproval && (htmlFile == null || !htmlFile.isFile())) {
            throw new GradleException("Public release ${releaseVersion} requires ${metadataFile} and ${htmlFile}")
        }
        Properties metadata = new Properties()
        metadataFile.withReader('UTF-8') { reader -> metadata.load(reader) }
        if(requireApproval) {
            if(!metadata.getProperty('status', '').trim().equalsIgnoreCase('approved')) {
                throw new GradleException("Release notes for ${releaseVersion} are still a draft. Stop, obtain user approval, then set status=approved in ${metadataFile}.")
            }
            return
        }
        if(metadata.getProperty('version', '').trim() != releaseVersion) {
            throw new GradleException("Release-note version must exactly match projectVersion ${releaseVersion}")
        }
        if(metadata.getProperty('title', '').trim().isEmpty()) {
            throw new GradleException("Release-note title is missing for ${releaseVersion}")
        }
        String html = htmlFile.getText('UTF-8')
        ['What', 'Added', 'Changed', 'Fixed', 'Removed', 'Before You Upgrade'].each { heading ->
            if(!html.contains(heading)) throw new GradleException("Release notes for ${releaseVersion} are missing the '${heading}' section")
        }
        def plainWords = html.replaceAll('<[^>]+>', ' ').trim().split('\\s+').findAll { !it.isBlank() }
        if(plainWords.size() < 150) {
            throw new GradleException("Release notes for ${releaseVersion} are too brief (${plainWords.size()} words)")
        }
    }
}
