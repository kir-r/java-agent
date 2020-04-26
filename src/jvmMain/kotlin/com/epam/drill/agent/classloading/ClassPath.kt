package com.epam.drill.agent.classloading

import com.epam.drill.agent.classloading.source.*
import java.io.*
import java.net.*
import java.util.jar.*

class ClassPath(
    private val includedPaths: Iterable<String>
) {
    private val scannedUrls = mutableSetOf<URL>()

    private val scannedNames = mutableSetOf<String>()

    private val scannedClasses = mutableSetOf<ClassSource>()

    fun scan(classLoaders: Iterable<ClassLoader>): MutableSet<ClassSource> {
        val allClassLoaders = classLoaders.flatMapTo(mutRefSet()) {
            it.parents(ClassLoader::getParent) + it
        }
        allClassLoaders.forEach { classLoader ->
            classLoader.urls().forEach { url ->
                url.scan(classLoader)
            }
        }
        return scannedClasses
    }

    private fun URL.scan(classloader: ClassLoader): Unit = takeIf { scannedUrls.add(it) }?.toFile()?.let { file ->
        if (file.isDirectory) {
            file.scan(::predicate, ::handler)
        } else file.useJarInputStream {
            it.scan(::predicate, ::handler)
            it.manifest?.classPath(this)?.forEach { url ->
                url.scan(classloader)
            }
        }
    } ?: Unit

    private fun Manifest.classPath(jarUrl: URL): Set<URL>? = mainAttributes.run {
        getValue(Attributes.Name.CLASS_PATH.toString())?.split(" ")
    }?.mapNotNullTo(mutableSetOf()) { path ->
        jarUrl.resolve(path)?.takeIf { it.protocol == "file" }
    }

    private fun predicate(name: String): Boolean = name.startsWithAnyOf(includedPaths) && name !in scannedNames

    private fun handler(source: ClassSource) {
        scannedNames.add(source.className)
        scannedClasses.add(source)
    }
}

private fun ClassLoader.urls(): List<URL> = when (this) {
    ClassLoader.getSystemClassLoader() -> parseJavaClassPath()
    is URLClassLoader -> urLs.toList()
    else -> emptyList()
}.filter { it.protocol == "file" }

private fun parseJavaClassPath(): List<URL> = System.getProperty("java.class.path")
    .split(File.pathSeparator)
    .mapNotNull(String::toUrl)


private fun String.toUrl(): URL? = try {
    File(this).toURI().toURL()
} catch (e: Exception) {
    when (e) {
        is SecurityException, is MalformedURLException -> null
        else -> throw e
    }
}

private fun URL.resolve(path: String): URL? = try {
    URL(this, path)
} catch (e: MalformedURLException) {
    null
}

private fun URL.toFile(): File? = try {
    File(toURI()).takeIf { it.exists() }  // Accepts escaped characters like %20.
} catch (e: Exception) { // URL.toURI() doesn't escape chars.
    when (e) {
        is URISyntaxException -> File(path) // Accepts non-escaped chars like space.
        else -> null
    }
}
