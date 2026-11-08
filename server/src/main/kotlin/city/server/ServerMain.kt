package city.server

import city.core.*
import kotlinx.coroutines.*
import java.nio.file.*

fun main(args: Array<String>) =
    runBlocking {
        fun option(
            name: String,
            default: String,
        ): String = args.indexOf(name).let { if (it >= 0) args.getOrNull(it + 1) ?: error("Missing $name") else default }
        if ("--help" in args) {
            println("server --host 127.0.0.1 --port 2000 --save saves/city.json --seed 2000 --size 64 --plugins plugins\nShared token: CITY_TOKEN environment variable. Ctrl+C saves and stops.")
            return@runBlocking
        }
        val catalog = Catalog()
        val plugins = PluginLoader(Path.of(option("--plugins", "plugins")), catalog)
        val path = Path.of(option("--save", "saves/city.json"))
        val city = if (Files.exists(path)) Persistence.load(path, catalog) else City.generate(option("--seed", "2000").toLong(), option("--size", "64").toInt())
        val server = CityServer(city, catalog, plugins.rules, option("--host", "127.0.0.1"), option("--port", "2000").toInt(), System.getenv("CITY_TOKEN") ?: "", path, systems = plugins.systems)
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
