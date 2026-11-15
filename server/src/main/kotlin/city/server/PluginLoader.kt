package city.server

import city.core.*
import java.net.URLClassLoader
import java.nio.file.*
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/** Plugins execute trusted JVM code. Keep this loader alive until server shutdown. */
class PluginLoader(
    directory: Path,
    catalog: Catalog,
) : AutoCloseable {
    private val loader =
        URLClassLoader(
            if (Files.isDirectory(directory)) {
                Files.list(directory).use { paths ->
                    paths
                        .filter { it.toString().endsWith(".jar") }
                        .sorted()
                        .map { it.toUri().toURL() }
                        .toList()
                        .toTypedArray()
                }
            } else {
                emptyArray()
            },
            CityPlugin::class.java.classLoader,
        )
    val systems: List<CityRule>
    val rules: List<TileRule>

    init {
        try {
            val context = PluginContext(catalog, mutableListOf())
            val ids = mutableSetOf<String>()
            ServiceLoader.load(CityPlugin::class.java, loader).sortedBy { it.id }.forEach {
                require(it.apiVersion == 1 && ids.add(it.id)) { "Incompatible or duplicate plugin: ${it.id}" }
                it.configure(context)
                println("Plugin loaded: ${it.id}")
            }
            require(
                context.rules
                    .map { it.id }
                    .distinct()
                    .size == context.rules.size,
            )
            require(
                context.systems
                    .map { it.id }
                    .distinct()
                    .size == context.systems.size,
            )
            systems = context.systems.toList()
            rules = context.rules.toList()
        } catch (failure: Throwable) {
            try {
                loader.close()
            } catch (closeFailure: Exception) {
                failure.addSuppressed(closeFailure)
            }
            if (failure is ServiceConfigurationError) {
                throw IllegalArgumentException("Invalid plugin configuration: ${failure.message}", failure)
            }
            throw failure
        }
    }

    override fun close() = loader.close()
}
