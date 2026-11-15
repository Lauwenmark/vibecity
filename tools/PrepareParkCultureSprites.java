import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.Map;
import javax.imageio.ImageIO;

/** Calibrate the generated ground diamonds to the renderer's 2:1 grid. */
class PrepareParkCultureSprites {
    public static void main(String[] args) throws Exception {
        // Measured lateral ground corners in the original PNGs (not tree/column tops).
        var corners = Map.of("zoo", 733, "park", 750, "large_park", 537, "library", 794, "tower", 945);
        Path output = Path.of("client/src/main/resources/city/client/civic");
        Files.createDirectories(output);
        for (var entry : corners.entrySet()) {
            var input = ImageIO.read(Path.of("assets/sprites/civic", entry.getKey() + "-v1.png").toFile());
            int left = input.getWidth(), right = -1, top = input.getHeight(), bottom = -1;
            for (int y = 0; y < input.getHeight(); y++) for (int x = 0; x < input.getWidth(); x++) {
                if ((input.getRGB(x, y) >>> 24) > 8) {
                    left = Math.min(left, x); right = Math.max(right, x);
                    top = Math.min(top, y); bottom = Math.max(bottom, y);
                }
            }
            if (right < left) throw new IllegalStateException("Empty sprite " + entry.getKey());
            double sx = 512.0 / (right - left + 1);
            double sy = 128.0 / (bottom - entry.getValue());
            double front = 0;
            for (int y = top; y <= bottom; y++) for (int x = left; x <= right; x++) {
                if ((input.getRGB(x, y) >>> 24) > 8) {
                    front = Math.max(front, (y - top) * sy + Math.abs((x - left) * sx - 255.5) / 2);
                }
            }
            var result = new BufferedImage(512, (int)Math.ceil(front) + 3, BufferedImage.TYPE_INT_ARGB);
            var g = result.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(input, new AffineTransform(sx, 0, 0, sy, -left * sx, -top * sy), null);
            g.dispose();
            ImageIO.write(result, "png", output.resolve(entry.getKey() + ".png").toFile());
            System.out.printf("%s: 512 x %d, scale %.4f / %.4f%n", entry.getKey(), result.getHeight(), sx, sy);
        }
    }
}
