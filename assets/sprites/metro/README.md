# Metro sprite proposals

## Surface entrance proposal v2

`station-v2.png` is a new transparent proposal: open descending stairwell, pale stone paving, dark metal railings and a white M on an azure-blue sign inspired by Belgian metro entrances. `station-v2-preview.png` shows it composited on a green background for alpha inspection. Exact generation and cleanup prompts are in [station-v2-prompts.json](station-v2-prompts.json), using the civic geometry guide as the camera reference. The target is the map's orthographic 2:1 projection, with ground-aligned edges at slopes ±0.5; the artwork remains an artistic approximation. Before runtime replacement, calibrate its ground footprint and anchor rather than forcing it into the old station's aspect ratio. This proposal does not change the packaged station or tunnels.

The complete set of 16 tunnel connection variants is available in [VARIANTS.md](VARIANTS.md), with exact generation prompts and connection masks in [tunnel-variants-v2.json](tunnel-variants-v2.json). The originals are retained as source artwork. Normalized derivatives are now integrated into CityRenderer; see the runtime section below.

Initial artwork generated with the built-in image_gen tool. Original PNGs retain transparency.

- `station-v1.png`: surface entrance with stairs, canopy and M sign.
- `tunnel-straight-v1.png`: straight underground track in cutaway.

These original images are retained for reproducibility. The v1 elongated tunnel is superseded at runtime by the normalized 16-variant set.

## Generation prompts

### Station

Use case: stylized-concept. Asset type: single game sprite for Shared City 2000, retro isometric city builder. Create one compact metro station entrance: descending stairwell, low cream concrete parapets, dark teal metal railings, small blue glazed canopy and one readable white M on a violet square sign. Orthographic 2:1 dimetric view, tile diamond twice as wide as tall, camera from southeast, no perspective convergence. Restrained pixel-art aesthetic, crisp stepped edges, simple readable shapes at small scale, muted gray concrete, teal and violet accents, light from upper left. One isolated object centered, fully visible with generous transparent margin, no ground landscape, no people, no train, no labels other than M, no watermark. Actual transparent background. This is an initial standalone sprite proposal, not a screenshot or spritesheet.

### Tunnel

Use case: stylized-concept. Asset type: single underground metro tunnel game sprite for Shared City 2000, retro isometric city builder. Create one straight short modular metro tunnel section in cutaway: concrete U-shaped trench with thick low sidewalls, two steel rails on dark sleepers, small violet utility conduit along inner wall. Roof removed so tracks visible. Rail axis runs upper-left to lower-right in a 2:1 dimetric orthographic projection; edges follow 2 horizontal pixels per 1 vertical pixel. Open at both ends, constant width, suitable as a single tile segment. Restrained pixel-art aesthetic, crisp stepped edges, simple readable shapes at small scale, muted gray concrete and teal shadows with violet accents, light from upper left. One isolated object centered fully visible with generous transparent margin. No terrain block, no station, no train, no text, no labels, no watermark, no perspective convergence. Actual transparent background. This is an initial standalone sprite proposal, not a screenshot or spritesheet.

## Runtime integration

- Packaged PNGs: `client/src/main/resources/city/client/metro/` (repository-relative).
- Tunnels: 16 sprites, 256 × 128 pixels, identical diamond footprint. Screen-facing bits NE=1, SE=2, SW=4, NW=8. Camera rotation selects another mask instead of rotating the lighting in the image.
- Original walls/textures are retained; track geometry, gauge, sleepers at the openings and buffers are rebuilt deterministically to prevent doubled or discontinuous rails. Four-way crossings are visually simplified.
- Station: 256 × 208 pixels, rendered at 32 × 26 game units with bottom anchor `(16, 26)`; its bottom is at tile center + 8 game units. The entrance is a camera-facing sprite.
- `MetroSprites` loads each image once from the classpath. Tunnel images follow the terrain's two triangles. Water pipes are painted above the tunnel; the underground M marker remains visible. Surface stations use the new entrance; diagnostic layers retain their conventional building representation.
- Regenerate with JDK 21 or later from the repository root: `java -Djava.awt.headless=true tools/PrepareMetroSprites.java`. The generated resources are checked in; Java tooling is not needed at game startup.
- Validate with `./gradlew :client:test :client:ktlintCheck :client:installDist`. The test gallery is generated at `client/build/previews/metro-sprites.png`.
