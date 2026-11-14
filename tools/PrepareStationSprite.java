import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Prepare the approved station illustration. Run from the repository root. */
class PrepareStationSprite {
    public static void main(String[] args) throws Exception {
        BufferedImage input = ImageIO.read(Path.of("assets/sprites/civic/station-esplanade-v1.png").toFile());
        int left = input.getWidth(), top = input.getHeight(), right = -1, bottom = -1;
        for (int y = 0; y < input.getHeight(); y++) for (int x = 0; x < input.getWidth(); x++) {
            if ((input.getRGB(x, y) >>> 24) > 8) {
                left = Math.min(left, x); right = Math.max(right, x);
                top = Math.min(top, y); bottom = Math.max(bottom, y);
            } else {
                input.setRGB(x, y, 0);
            }
        }
        if (right < left) throw new IllegalStateException("Empty station sprite");
        // Ground side corners are at y=565; calibrate the base to the game's 2:1 grid.
        // This does not correct the illustration's internal architectural perspective.
        double verticalScale = 128.0 / (bottom - 565);
        int artworkHeight = (int)Math.round((bottom - top + 1) * verticalScale);
        // The painted curb is slightly asymmetric: reserve eight pixels below it
        // so its full thickness remains inside the footprint at every rotation.
        BufferedImage result = new BufferedImage(512, artworkHeight + 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(input, 0, 0, result.getWidth(), artworkHeight, left, top, right + 1, bottom + 1, null);
        g.dispose();
        Path output = Path.of("client/src/main/resources/city/client/civic/station.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(result, "png", output.toFile());
    }
}
