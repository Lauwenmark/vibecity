package city.server

import city.core.*
import kotlinx.coroutines.*
import java.nio.file.*

fun main(args: Array<String>) =
    runBlocking {
        fun option(
            name: String,
            default: String,
        ): String = serverOption(args, name) ?: default
        if ("--help" in args) {
            println("server --host 127.0.0.1 --port 2000 --save saves/city.json --seed 2000 --size 64 --plugins plugins [--test-mode]\n--test-mode: ignore construction years; new cities start with 1,000,000. Existing saves keep their treasury.\nShared token: CITY_TOKEN environment variable. Ctrl+C saves and stops.")
            return@runBlocking
        }
        val catalog = Catalog()
        val plugins = PluginLoader(Path.of(option("--plugins", "plugins")), catalog)
        val path = Path.of(option("--save", "saves/city.json"))
        val city = loadServerCity(args, path, catalog)
        val server = CityServer(city, catalog, plugins.rules, option("--host", "127.0.0.1"), option("--port", "2000").toInt(), System.getenv("CITY_TOKEN") ?: "", path, systems = plugins.systems, testMode = "--test-mode" in args)
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runBlocking {
                    try {
                        server.shutdown()
                    } finally {
                        plugins.close()
                    }
                }
            },
        )
        println("Shared City server listening on ${option("--host","127.0.0.1")}:${server.port}; ${city.width}×${city.height}; ${Runtime.getRuntime().availableProcessors()} CPU workers available")
        awaitCancellation()
    }

internal fun serverOption(
    args: Array<String>,
    name: String,
): String? {
    for ((index, argument) in args.withIndex()) {
        val value =
            when {
                argument == name -> args.getOrNull(index + 1)
                argument.startsWith("$name=") -> argument.substringAfter('=')
                else -> continue
            }
        require(!value.isNullOrBlank() && !value.startsWith("--")) { "Missing value for $name" }
        return value
    }
    return null
}

internal fun loadServerCity(
    args: Array<String>,
    path: Path,
    catalog: Catalog,
    report: (String) -> Unit = ::println,
): City {
    if (Files.exists(path)) {
        val city = Persistence.load(path, catalog)
        report("Loaded save ${path.toAbsolutePath()}: ${city.width}×${city.height}.")
        if (serverOption(args, "--size") != null || serverOption(args, "--seed") != null) {
            report("--size and --seed only apply to new maps and were ignored. Use --save with a new file path to generate a new map.")
        }
        return city
    }
    val city = City.generate((serverOption(args, "--seed") ?: "2000").toLong(), (serverOption(args, "--size") ?: "64").toInt())
    val initial = if ("--test-mode" in args) city.copy(treasury = 1_000_000) else city
    report("Generated new map: ${city.width}×${city.height}; save path ${path.toAbsolutePath()}.")
    return initial
}
