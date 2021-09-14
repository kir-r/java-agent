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
package com.epam.drill.agent.instrument

import com.alibaba.ttl.threadpool.agent.internal.javassist.*
import com.epam.drill.*
import com.epam.drill.kni.*
import com.epam.drill.logger.*
import com.epam.drill.request.*
import java.io.*
import kotlin.reflect.jvm.*

@Kni
actual object CadenceTransformer {

    private val logger = Logging.logger(CadenceTransformer::class.jvmName)

    actual fun transform(
        className: String,
        classfileBuffer: ByteArray,
        loader: Any?,
    ): ByteArray? {
        return try {
            ClassPool.getDefault().appendClassPath(LoaderClassPath(loader as? ClassLoader))
            when (className) {
                CADENCE_PRODUCER -> producerInstrument(classfileBuffer)
                CADENCE_CONSUMER -> consumerInstrument(classfileBuffer)
                else -> null
            }
        } catch (e: Exception) {
            logger.warn(e) { "Instrumentation error" }
            null
        }
    }

    private fun producerInstrument(
        classfileBuffer: ByteArray,
    ) = ClassPool.getDefault().makeClass(ByteArrayInputStream(classfileBuffer))?.run {
        val constructors: List<Pair<CtConstructor, Int>> = constructors.mapNotNull { constructor ->
            constructor.parameterTypes.indexOfFirst { clazz ->
                clazz.name.replace(".", "/") == "com/uber/cadence/client/WorkflowOptions"
            }.takeIf { it >= 0 }?.let { constructor to it + 1 /* 0 - index of "this" object */ }
        }
        constructors.forEach { (constructor, paramIndex) ->
            constructor.insertBefore("""
                if ($$paramIndex.getMemo() == null) {
                    $$paramIndex = new com.uber.cadence.client.WorkflowOptions.Builder($$paramIndex).setMemo(new java.util.HashMap()).build();
                }
            """.trimIndent())
        }

        listOf(
            "signalAsync",
            "signalAsyncWithTimeout",
            "start",
            "startAsync",
            "startAsyncWithTimeout",
            "signalWithStart",
        ).mapNotNull { methodName ->
            runCatching {
                getDeclaredMethod(methodName)
            }.onFailure {
                logger.error { "Method `$methodName` not found, check candence api version" }
            }.getOrNull()
        }.forEach {
            it.insertBefore("""
                java.util.Map drillHeaders = ${HttpRequest::class.java.name}.INSTANCE.${HttpRequest::loadDrillHeaders.name}();
                if (drillHeaders != null) {
                    java.util.Iterator iterator = drillHeaders.entrySet().iterator();
                     if (getOptions().isPresent()) {
                        com.uber.cadence.client.WorkflowOptions options = (com.uber.cadence.client.WorkflowOptions) getOptions().get();
                        if (options.getMemo() != null) {
                            while (iterator.hasNext()) {
                                java.util.Map.Entry entry = (java.util.Map.Entry) iterator.next();
                                options.getMemo().put(((String) entry.getKey()), entry.getValue());
                            }
                        }
                     }
                }
            """.trimIndent())
        }
        toBytecode()
    }

    private fun consumerInstrument(
        classfileBuffer: ByteArray,
    ) = ClassPool.getDefault().makeClass(ByteArrayInputStream(classfileBuffer))?.run {
        getDeclaredMethod("run").also {
            it.insertBefore("""
                java.util.Map drillHeaders = new java.util.HashMap();
                com.uber.cadence.Memo memo = attributes.getMemo();
                if (memo != null) {
                    java.util.Map fields = memo.getFields();
                    if (fields != null) {
                        java.util.Iterator iterator = fields.entrySet().iterator(); 
                        while (iterator.hasNext()) {
                            java.util.Map.Entry entry = (java.util.Map.Entry) iterator.next();
                            String key = ((String) entry.getKey());
                            if (key.startsWith("drill-")) {
                                java.nio.ByteBuffer byteBuffer = (java.nio.ByteBuffer) entry.getValue(); 
                                if (byteBuffer != null) {
                                    final byte[] valueBytes = new byte[byteBuffer.remaining()];
                                    byteBuffer.mark(); 
                                    byteBuffer.get(valueBytes); 
                                    byteBuffer.reset();
                                    String value = (String) com.uber.cadence.converter.JsonDataConverter.getInstance().fromData(valueBytes, String.class, String.class);
                                    drillHeaders.put(key, value);
                                }
                            }
                        }
                        ${HttpRequest::class.java.name}.INSTANCE.${HttpRequest::storeDrillHeaders.name}(drillHeaders);
                    }
                }
            """.trimIndent())
        }
        toBytecode()
    }

}