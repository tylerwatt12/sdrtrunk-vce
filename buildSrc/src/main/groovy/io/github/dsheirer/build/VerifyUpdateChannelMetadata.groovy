package io.github.dsheirer.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class VerifyUpdateChannelMetadata extends DefaultTask {
    @Input String releaseVersion
    @Input String updateTrack
    @Input String updateBuild

    @TaskAction
    void verify() {
        long numericBuild
        try {
            numericBuild = Long.parseLong(updateBuild)
        }
        catch(NumberFormatException e) {
            throw new GradleException('updateBuild exceeds the supported 64-bit integer range', e)
        }
        if(updateTrack == 'none') {
            if(VerifyReleaseNotes.numbered(releaseVersion)) {
                throw new GradleException("Numbered release ${releaseVersion} must use the alpha update channel")
            }
            if(numericBuild != 0L) throw new GradleException('A build with updateTrack=none must use updateBuild=0')
        }
        else if(updateTrack == 'alpha') {
            if(!(releaseVersion ==~ /\d+\.\d+\.\d+-alpha-\d+/)) {
                throw new GradleException("Alpha update metadata requires an Alpha version, not ${releaseVersion}")
            }
            if(numericBuild <= 0L) throw new GradleException('An Alpha package must have a positive updateBuild')
        }
        else if(updateTrack == 'nightly') {
            if(!releaseVersion.toLowerCase(Locale.US).contains('nightly')) {
                throw new GradleException("Nightly update metadata requires a nightly version, not ${releaseVersion}")
            }
            if(numericBuild <= 0L) throw new GradleException('A nightly package must have a positive updateBuild')
        }
    }
}
