package city.client

import javax.swing.*

fun main(args: Array<String>) {
    if ("--help" in args) {
        println("client [--local] [--host localhost --port 2000 --name Maire]\nCITY_TOKEN supplies the server token.")
        return
    }

    fun option(
        name: String,
        default: String,
    ) = args.indexOf(name).let { if (it >= 0) args.getOrNull(it + 1) ?: error("Missing $name") else default }
    UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName())
    SwingUtilities.invokeLater {
        val window = CityWindow()
        window.isVisible = true
        if ("--local" in args) {
            window.startLocal()
        } else if ("--host" in args) {
            window.connect(option("--host", "127.0.0.1"), option("--port", "2000").toInt(), option("--name", "Maire"), System.getenv("CITY_TOKEN") ?: "")
        }
    }
}
