package city.client

import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Path2D
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/** Small solid cars: the road axes follow the slope, while height remains vertical on screen. */
internal class CarArtwork(
    dx: Double,
    dy: Double,
    variant: Int,
) {
    private val faces = mutableListOf<Pair<Color, Path2D.Double>>()

    init {
        val length = hypot(dx, dy)
        val ux = dx / length
        val uy = dy / length
        val vx = -sign(dy) * 0.8944
        val vy = sign(dx) * 0.4472
        val paint = Color(PALETTE[variant % PALETTE.size])
        val rear = if (variant % 3 == 0) -1.9 else -1.35
        val roof = if (variant % 3 == 0) 2.65 else 2.35

        fun face(
            color: Color,
            vararg points: Double,
        ) {
            val path = Path2D.Double()
            for (i in points.indices step 3) {
                val x = ux * points[i] + vx * points[i + 1]
                val y = uy * points[i] + vy * points[i + 1] - points[i + 2]
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.closePath()
            faces.add(color to path)
        }

        // Contact shadow and two visible tyres, below the painted sill.
        face(Color(0, 0, 0, 65), -3.5, -1.25, 0.0, 3.5, -1.25, 0.0, 3.5, 1.25, 0.0, -3.5, 1.25, 0.0)
        val side = sign(vy)
        for (axle in listOf(-2.1, 2.0)) {
            face(Color(0x20252b), axle - .55, side * 1.1, .05, axle + .55, side * 1.1, .05, axle + .55, side * 1.1, 1.05, axle - .55, side * 1.1, 1.05)
        }
        face(paint.darker(), -3.3, side, .5, 3.3, side, .5, 3.3, side, 1.35, -3.3, side, 1.35)
        val end = if (uy >= 0) 3.3 else -3.3
        face(paint.darker().darker(), end, -1.0, .5, end, 1.0, .5, end, 1.0, 1.35, end, -1.0, 1.35)
        face(paint, -3.3, -1.0, 1.35, 3.3, -1.0, 1.35, 3.3, 1.0, 1.35, -3.3, 1.0, 1.35)
        // Sloped glazing encloses a smaller roof, leaving a distinct bonnet and boot.
        face(Color(0x344d60), rear - .45, side, 1.35, 1.65, side, 1.35, .85, side * .78, roof, rear, side * .78, roof)
        face(if (uy >= 0) Color(0xa6d5df) else Color(0x52768c), if (uy >= 0) 1.65 else rear - .45, -1.0, 1.35, if (uy >= 0) 1.65 else rear - .45, 1.0, 1.35, if (uy >= 0) .85 else rear, .78, roof, if (uy >= 0) .85 else rear, -.78, roof)
        face(paint.brighter(), rear, -.78, roof, .85, -.78, roof, .85, .78, roof, rear, .78, roof)
        face(paint, -.35, side, 1.35, -.12, side, 1.35, -.12, side * .78, roof, -.35, side * .78, roof)
        for (lamp in listOf(-.72, .72)) {
            face(if (end > 0) Color(0xfff1c4) else Color(0xd64c43), end, lamp - .2, .85, end, lamp + .2, .85, end, lamp + .2, 1.2, end, lamp - .2, 1.2)
        }
        // Light catches the upper edge of the bonnet.
        if (abs(uy) > .05) face(paint.brighter(), 1.8, -1.0, 1.36, 3.15, -1.0, 1.36, 3.15, -.8, 1.36, 1.8, -.8, 1.36)
    }

    fun paint(g: Graphics2D) {
        for ((color, path) in faces) {
            g.color = color
            g.fill(path)
        }
    }

    private companion object {
        val PALETTE = intArrayOf(0xd65b48, 0x4b91bc, 0xe4b94f, 0xdce3df, 0x63977d, 0x8c79ac)
    }
}
