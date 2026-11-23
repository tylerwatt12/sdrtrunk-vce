package io.github.dsheirer.build

import org.gradle.api.Action
import org.gradle.api.file.FileCopyDetails

class ApplicationLauncherPermissionsAction implements Action<FileCopyDetails>, Serializable {
    private final String applicationName

    ApplicationLauncherPermissionsAction(String applicationName) { this.applicationName = applicationName }

    @Override
    void execute(FileCopyDetails details) {
        if(details.relativePath.pathString.endsWith("/bin/${applicationName}")) {
            details.permissions { unix('rwxr-xr-x') }
        }
    }
}
