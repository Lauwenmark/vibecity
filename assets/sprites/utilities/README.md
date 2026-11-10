# Power and water utility sprites

Ten transparent sprites generated with the built-in `image_gen` tool. Original artwork is retained as `*-v1.png`; exact prompts are in [prompts.json](prompts.json).

- Power: coal, oil, gas, nuclear, wind, solar, fusion, hydro and microwave.
- Water: pump (water pumping station).

Run `java -Djava.awt.headless=true tools/PrepareUtilitySprites.java` from the repository root to reproduce the packaged PNGs in `client/src/main/resources/city/client/utilities/`. This only crops transparent margins and downsamples to 512 pixels wide, preserving aspect ratio and alpha.

`BuildingSprites` loads the images once. On the surface layer, each installation occupies its full catalog footprint and is painted once at the frontmost footprint cell for the current camera rotation. Artwork stays screen-facing, like the metro station; these are not four directional views. Analytical layers retain their existing symbolic blocks. The renderer's conservative bounds include the larger installation silhouettes for chunk rendering and invalidation.

Run `./gradlew :client:test` to validate rendering, including all four camera rotations and clipped scenes. The utility test writes an in-game gallery to `client/build/previews/utility-sprites.png`.
