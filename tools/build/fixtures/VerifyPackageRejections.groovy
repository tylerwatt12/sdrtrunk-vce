package io.github.dsheirer.build

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

/** The fixture supplies damaged ZIPs; the production verifier decides whether each one is admissible. */
abstract class VerifyPackageRejections extends DefaultTask {
    @InputFile File specificationFile
    @InputDirectory File casesDirectory

    @TaskAction
    void verifyRejections() {
        Map specification = new JsonSlurper().parse(specificationFile) as Map
        List cases = new JsonSlurper().parse(new File(casesDirectory, 'cases.json')) as List
        for(Map fixture : cases) {
            Map targetValues = new LinkedHashMap(specification.target)
            targetValues.putAll(fixture.targetOverrides as Map)
            targetValues.archiveFile = new File(targetValues.archiveFile as String)
            RuntimeTarget target = new RuntimeTarget(targetValues)
            Throwable rejection = null
            try {
                RuntimePackageVerifier.verify(target, new File(fixture.archive as String), specification.metadata as Map)
            }
            catch(Exception failure) {
                rejection = failure
            }
            String expected = fixture.expected as String
            if(expected.isEmpty()) {
                if(rejection != null) throw new AssertionError("Valid control ${fixture.name} was rejected", rejection)
            }
            else {
                if(rejection == null) throw new AssertionError("Damaged package ${fixture.name} was accepted")
                if(!rejection.message.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT))) {
                    throw new AssertionError("${fixture.name} failed for the wrong reason: ${rejection.message}", rejection)
                }
            }
            logger.lifecycle("Checked package fixture ${fixture.name}")
        }
    }
}
