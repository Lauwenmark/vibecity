# Power and water utility sprites

Ten transparent sprites generated with the built-in `image_gen` tool. The nine power installations use the v2 artwork in [proposals-v2](proposals-v2/README.md), with exact prompts in [proposals-v2/prompts.json](proposals-v2/prompts.json). Original artwork is retained as `*-v1.png`; original prompts are in [prompts.json](prompts.json). The water pump still uses `pump-v1.png`.

- Power: coal, oil, gas, nuclear, wind, solar, fusion, hydro and microwave.
- Water: pump (water pumping station).

Run `java -Djava.awt.headless=true tools/PrepareUtilitySprites.java` from the repository root to reproduce the packaged PNGs in `client/src/main/resources/city/client/utilities/`. This crops transparent margins and downsamples to 512 pixels wide, preserving alpha. For power installations, measured lateral ground corners calibrate the vertical scale: the front tip is 128 pixels below those corners, matching the map's 2:1 footprint. This normalization scales the whole illustration; it does not redraw individual architectural details. The pump retains its original aspect ratio.

`BuildingSprites` loads the images once. On the surface layer, each installation occupies its full catalog footprint and is painted once at the frontmost footprint cell for the current camera rotation. Artwork stays screen-facing, like the metro station; these are not four directional views. Analytical layers retain their existing symbolic blocks. The renderer's conservative bounds include the larger installation silhouettes for chunk rendering and invalidation.

Run `./gradlew :client:test` to validate rendering, including all four camera rotations and clipped scenes. The test also checks that the power and civic sprites stay above the two front footprint edges, with two source pixels of antialiasing tolerance. The utility test writes an in-game gallery to `client/build/previews/utility-sprites.png`.
