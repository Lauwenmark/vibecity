import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Calibrated alpha crop and downsample; originals remain untouched. Run from repository root. */
class PrepareUtilitySprites {
    public static void main(String[] args) throws Exception {
        Path source = Path.of("assets/sprites/utilities");
        Path output = Path.of("client/src/main/resources/city/client/utilities");
        Files.createDirectories(output);
        for (String id : java.util.List.of("coal", "oil", "gas", "nuclear", "wind", "solar", "fusion", "hydro", "microwave", "pump")) {
            Path path = id.equals("pump") ? source.resolve("pump-v1.png") : source.resolve("proposals-v2/" + id + "-v2.png");
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
            // Measured lateral ground corners in the v2 originals. Make the vertical
            // distance from these corners to the front tip exactly 512 / 4 pixels.
            // Pump keeps its original aspect ratio; it is not part of the power refresh.
            int sideY = switch (id) {
                case "coal" -> 830;
                case "oil" -> 854;
                case "gas" -> 846;
                case "nuclear" -> 781;
                case "wind" -> 898;
                case "solar" -> 784;
                case "fusion" -> 816;
                case "hydro" -> 846;
                case "microwave" -> 796;
                default -> -1;
            };
            double verticalScale = sideY < 0 ? 512.0 / width : 128.0 / (bottom - sideY);
            BufferedImage compact = new BufferedImage(512, Math.max(1, (int)Math.round(verticalScale * height)), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = compact.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(input, 0, 0, compact.getWidth(), compact.getHeight(), left, top, right + 1, bottom + 1, null);
            g.dispose();
            ImageIO.write(compact, "png", output.resolve(id + ".png").toFile());
        }
    }
}
