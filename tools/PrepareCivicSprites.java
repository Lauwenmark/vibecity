import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Calibrated alpha crop and downsample; originals remain untouched. Run from repository root. */
class PrepareCivicSprites {
    public static void main(String[] args) throws Exception {
        Path source = Path.of("assets/sprites/civic");
        Path output = Path.of("client/src/main/resources/city/client/civic");
        Files.createDirectories(output);
        try (var files = Files.list(source)) {
            for (Path path : files.filter(p -> java.util.Set.of("fire-v5.png", "school-v5.png", "police-v2.png", "stadium-v1.png",
                    "mayor-approved-v1.png", "cityhall-approved-v1.png", "hospital-approved-v1.png", "prison-approved-v1.png",
                    "treatment-v1.png", "museum-v2.png").contains(p.getFileName().toString())).sorted().toList()) {
                BufferedImage input = ImageIO.read(path.toFile());
                int left = input.getWidth(), top = input.getHeight(), right = -1, bottom = -1;
                for (int y = 0; y < input.getHeight(); y++) for (int x = 0; x < input.getWidth(); x++) {
                    if ((input.getRGB(x, y) >>> 24) > 8) {
                        left = Math.min(left, x); right = Math.max(right, x);
                        top = Math.min(top, y); bottom = Math.max(bottom, y);
                    }
                }
                if (right < left) throw new IllegalStateException("Empty sprite: " + path);
                if (path.getFileName().toString().contains("-approved-") ||
                        java.util.Set.of("treatment-v1.png", "museum-v2.png").contains(path.getFileName().toString())) {
                    // Preserve the approved artwork: uniform scaling only, no shear or repainting.
                    // Transparent space below the picture accommodates its actual contour inside
                    // the renderer's 2:1 footprint, including irregular ground outlines.
                    double center = (left + right) / 2.0;
                    double front = bottom;
                    for (int y = top; y <= bottom; y++) for (int x = left; x <= right; x++) {
                        if ((input.getRGB(x, y) >>> 24) > 8) front = Math.max(front, y + Math.abs(x - center) / 2.0);
                    }
                    double scale = 512.0 / (right - left + 1);
                    int height = (int)Math.ceil((front - top + 2) * scale);
                    BufferedImage compact = new BufferedImage(512, height, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = compact.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g.drawImage(input, new java.awt.geom.AffineTransform(scale, 0, 0, scale, -left * scale, -top * scale), null);
                    g.dispose();
                    ImageIO.write(compact, "png", output.resolve(path.getFileName().toString().replace("-approved-v1", "").replaceAll("-v[0-9]+", "")).toFile());
                    continue;
                }
                int width = right - left + 1, height = bottom - top + 1;
                // Measured left/right ground corners in the selected source images.
                // Normalize the ground axes to the game's exact 2:1 projection.
                int sideY = switch (path.getFileName().toString()) {
                    case "fire-v5.png" -> 787;
                    case "police-v2.png" -> 870;
                    case "school-v5.png" -> 790;
                    case "stadium-v1.png" -> 501;
                    default -> throw new IllegalArgumentException("Uncalibrated sprite: " + path);
                };
                double verticalScale = 128.0 / (bottom - sideY);

                BufferedImage compact = new BufferedImage(512, Math.max(1, (int)Math.round(verticalScale * height)), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = compact.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(input, 0, 0, compact.getWidth(), compact.getHeight(), left, top, right + 1, bottom + 1, null);
                g.dispose();
                ImageIO.write(compact, "png", output.resolve(path.getFileName().toString().replaceAll("-v[0-9]+", "")).toFile());
            }
        }
    }
}
