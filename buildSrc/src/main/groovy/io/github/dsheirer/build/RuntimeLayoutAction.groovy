package io.github.dsheirer.build

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.file.FileCopyDetails
import org.gradle.api.file.RelativePath

/** Restorable ZIP copy transformation; captures only the immutable runtime descriptor. */
class RuntimeLayoutAction implements Action<FileCopyDetails>, Serializable {
    private final RuntimeTarget target

    RuntimeLayoutAction(RuntimeTarget target) { this.target = target }

    @Override
    void execute(FileCopyDetails details) {
        String[] segments = details.relativePath.segments
        if(segments.length < 2 || segments[0] != target.packageRoot || segments[1] != target.archiveRoot) {
            throw new GradleException("Unexpected top-level entry in ${target.archiveName}: ${details.path}")
        }
        if(segments.length == 2) {
            details.exclude()
            return
        }
        String[] strippedSegments = segments.drop(2) as String[]
        if(!target.windows && strippedSegments[0] == 'legal' && details.file.length() == 0) {
            details.exclude()
            return
        }
        if(strippedSegments.length == 1 && strippedSegments[0] == 'LICENSE') strippedSegments = ['RUNTIME-LICENSE'] as String[]
        details.relativePath = new RelativePath(details.relativePath.isFile(),
                ([target.packageRoot] + strippedSegments.toList()) as String[])
        if(!target.windows) {
            String strippedPath = strippedSegments.join('/')
            if(strippedPath.startsWith('bin/') || strippedPath == 'lib/jexec' || strippedPath == 'lib/jspawnhelper') {
                details.permissions { unix('rwxr-xr-x') }
            }
        }
    }
}
