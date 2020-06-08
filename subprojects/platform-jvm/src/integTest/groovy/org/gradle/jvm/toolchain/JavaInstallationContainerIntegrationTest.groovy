/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.jvm.toolchain

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.AvailableJavaHomes
import spock.lang.IgnoreIf

class JavaInstallationContainerIntegrationTest extends AbstractIntegrationSpec {

    @IgnoreIf({ AvailableJavaHomes.differentVersion == null })
    def "can add a new installation to javaInstallations"() {
        def someJdk = AvailableJavaHomes.differentVersion

        buildFile << """
        plugins {
            id 'java'
        }
        task('showInstallation') {
          doLast {
             println project.javaToolchains.query()
          }
        }
"""

        settingsFile << """
        plugins {
            id 'java-installations'
        }

        javaInstallations {
            register("someJdk") {
                path = file("${someJdk.javaHome.absolutePath}")
            }
        }
"""

        when:
        run("showInstallation")

        then:
        outputContains("someJdk (" + someJdk.javaHome.absolutePath)
        outputContains("current (" + System.getProperty("java.home"))

    }


}
