/*
 * Copyright 2019 Open Web IT B.V. (https://www.openweb.nl/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import nl.openweb.hippo.groovy.annotations.Bootstrap
import nl.openweb.hippo.groovy.annotations.Updater
import nl.openweb.tools.Tools
import org.onehippo.repository.update.BaseNodeUpdateVisitor

import javax.jcr.Node

@Updater(name = "Spaces are not supposed to be in a filename",
        xpath = "//element(*, hippo:document)")
@Bootstrap(reload = true)
class TheUpdaterWithSpaces extends BaseNodeUpdateVisitor {
    boolean doUpdate(Node node) {
        log.info(Tools.getEnvironmentInfo())
        log.info "manipulate node < > & an %^&* /> {}", node.path
        return true
    }

    boolean undoUpdate(Node node) {
        throw new UnsupportedOperationException('Updater does not implement undoUpdate method')
    }
}