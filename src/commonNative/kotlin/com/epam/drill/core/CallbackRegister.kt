/**
 * Copyright 2020 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.epam.drill.core

import com.epam.drill.*
import com.epam.drill.agent.*
import com.epam.drill.agent.classloading.*
import com.epam.drill.common.*
import com.epam.drill.common.serialization.*
import com.epam.drill.core.plugin.loader.*
import com.epam.drill.logger.*
import com.epam.drill.plugin.*
import com.epam.drill.request.*
import kotlinx.coroutines.*
import kotlinx.serialization.protobuf.*

@SharedImmutable
private val logger = Logging.logger("CallbackLogger")

fun globalCallbacks(): Unit = run {
    getClassesByConfig = {
        val classScanDelay = config.classScanDelay - state.startMark.elapsedNow()
        if (classScanDelay.isPositive()) {
            logger.debug { "Waiting class scan delay ($classScanDelay left)..." }
            delay(classScanDelay)
        }
        when (waitForMultipleWebApps(config.webAppLoadingTimeout)) {
            null -> logger.warn {
                val timedOut = state.webApps.filterValues { !it }.keys
                "Apps: $timedOut have not initialized in ${config.webAppLoadingTimeout}. " +
                    "Please check the app names or increase the timeout"
            }
            else -> logger.info { "app is initialized" }
        }
        DataService.retrieveClassesData(PackagesPrefixes.serializer() stringify agentConfig.packagesPrefixes)
    }

    setPackagesPrefixes = { prefixes ->
        agentConfig = agentConfig.copy(packagesPrefixes = prefixes)
        updateState {
            copy(
                alive = true,
                packagePrefixes = prefixes.packagesPrefixes
            )
        }
    }

    sessionStorage = RequestHolder::storeRequestMetadata
    closeSession = {
        RequestHolder.closeSession()
        PluginExtension.processServerResponse()
    }
    drillRequest = RequestHolder::get

    loadPlugin = ::loadJvmPlugin
    nativePlugin = { _, _, _ -> null }

}

fun RequestHolder.storeRequestMetadata(request: DrillRequest) {
    store(ProtoBuf.dump(DrillRequest.serializer(), request))
}

fun RequestHolder.get(): DrillRequest? {
    return dump()?.let { ProtoBuf.load(DrillRequest.serializer(), it) }
}
