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
package com.epam.drill.request

import com.alibaba.ttl.*
import com.epam.drill.common.*
import com.epam.drill.kni.*
import com.epam.drill.logger.*
import com.epam.drill.plugin.*
import com.epam.drill.plugin.api.processing.*
import kotlinx.serialization.protobuf.*
import kotlin.reflect.jvm.*

@Kni
actual object RequestHolder {
    private val logger = Logging.logger(RequestHolder::class.jvmName)

    private lateinit var threadStorage: InheritableThreadLocal<DrillRequest>

    val agentContext: AgentContext = RequestAgentContext { threadStorage.get() }

    actual fun init(isAsync: Boolean) {
        threadStorage = if (isAsync) TransmittableThreadLocal() else InheritableThreadLocal()
    }

    actual fun store(drillRequest: ByteArray) {
        storeRequest(ProtoBuf.load(DrillRequest.serializer(), drillRequest))
    }

    fun storeRequest(drillRequest: DrillRequest) {
        threadStorage.set(drillRequest)
        logger.trace { "session ${drillRequest.drillSessionId} saved" }
        PluginExtension.processServerRequest()
    }

    actual fun dump(): ByteArray? {
        return threadStorage.get()?.let { ProtoBuf.dump(DrillRequest.serializer(), it) }
    }

    actual fun closeSession() {
        logger.trace { "session ${threadStorage.get()} closed" }
        threadStorage.remove()
    }
}
