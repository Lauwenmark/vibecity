import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Mechanical alpha crop and downsample; originals remain untouched. Run from repository root. */
class PrepareCivicSprites {
    public static void main(String[] args) throws Exception {
        Path source = Path.of("assets/sprites/civic");
        Path output = Path.of("client/src/main/resources/city/client/civic");
        Files.createDirectories(output);
        try (var files = Files.list(source)) {
            for (Path path : files.filter(p -> p.toString().endsWith("-v1.png")).sorted().toList()) {
                BufferedImage input = ImageIO.read(path.toFile());
                int left = input.getWidth(), top = input.getHeight(), right = -1, bottom = -1;
                for (int y = 0; y < input.getHeight(); y++) for (int x = 0; x < input.getWidth(); x++) {
                    if ((input.getRGB(x, y) >>> 24) > 8) {
                        left = Math.min(left, x); right = Math.max(right, x);
                        top = Math.min(top, y); bottom = Math.max(bottom, y);
                    }
                }
                if (right < left) throw new IllegalStateException("Empty sprite: " + path);
                int width = right - left + 1, height = bottom - top + 1;
                BufferedImage compact = new BufferedImage(512, Math.max(1, (int)Math.round(512.0 * height / width)), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = compact.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(input, 0, 0, compact.getWidth(), compact.getHeight(), left, top, right + 1, bottom + 1, null);
                g.dispose();
                ImageIO.write(compact, "png", output.resolve(path.getFileName().toString().replace("-v1", "")).toFile());
            }
        }
    }
}
