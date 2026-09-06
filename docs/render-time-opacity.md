# Render-time player opacity

Player opacity uses a `DrawCallbacks.drawTemp` model wrapper with the built-in
GPU plugin and 117 HD's `rs117.hd.renderer.zone.ZoneRenderer`. Renderer detection
is deliberately narrow. HD is optional, and its classes are not linked into the
plugin. HD's legacy renderer and other renderers keep the previous opacity path.

Installation and removal wait for a stable logged-in/login-screen state. A map
loader retains the callback which started its load, and the client skips its
scene swap if that identity changes before completion. Replacing HD's callback
mid-load can consequently cause `Double zone load!` and shut HD down. An already
installed wrapper remains attached during subsequent loads. On plugin shutdown
it immediately stops applying opacity but defers removal until loading finishes.

The wrapper supplies face transparency without modifying native model arrays.
GPU consumes the view synchronously. The inspected HD zone renderer copies the
arrays on the calling thread before returning from `drawTemp`, even when upload
work runs asynchronously. This permits reuse of our per-player scratch array.
Recheck that ownership contract when HD changes its model-streaming implementation.

Boss colour-override decisions still run before opacity/floor selection. Normal
combat 0% culling and the Verzik/monkey-room 1% floors are unchanged. Previously
unsupported models can fade while either supported renderer wrapper is active.

HD's fire/infernal cape material shader bypasses partial model alpha. For affected
players at calculated 1-99% opacity, the HD-only fallback sets those faces (vanilla
textures 40/59) to alpha 255 in our private array. HD skips fully transparent faces
before material selection. This hides the cape completely; it does not fade it
proportionally. Other faces retain normal opacity, and built-in GPU is unchanged.
At 100% or a qualifying boss override, the original model is passed through.
The 0% combat cull and 0% -> 1% floors still apply before the cape fallback.
No HD materials, shaders, settings, or jars are modified.

Clear Ground Self/Others still remove cape, shield, legs, and boots, but no longer
force the remaining body to 100%. The normal self/other opacity, distance fading,
zero-opacity rules, and boss-override protection apply alongside equipment removal.
At 100%, Clear Ground still removes its selected equipment unless a qualifying
boss override is active. Renderer support/fallback rules remain the same as for
players without Clear Ground.

Wrapping the renderer callback is not a dedicated plugin-extension
API and can conflict with other plugins which wrap the same callback.

## Verification

Run `./gradlew build` to build the plugin. The additional regression tests and
HD-specific test configuration used during development are kept locally and
are not included in this repository. Local checks cover renderer detection,
switching, opacity policy, and the installed HD asynchronous alpha-copy and
face-transparency implementations, without starting an OpenGL context. These
checks do not replace in-game visual testing or compatibility review after an
HD update.

In-game checks: select HD's zone renderer, turn distance fading off, test other
players at 50% and 1% during attacks/drinking, and switch between GPU and HD.
The debug log identifies the renderer when the opacity wrapper is installed.
Also test fire/infernal capes at 1%, 50%, 99%, and 100% on HD and GPU, then toggle
Clear Ground Self/Others and check both body opacity and equipment restoration.
