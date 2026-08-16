package ai.rever.boss.plugin.runtime

import kotlinx.serialization.json.Json

/**
 * The JSON used to put a holder's state on the wire.
 *
 * **`encodeDefaults = true`, and that is the whole reason this exists.** kotlinx omits any
 * property still equal to its default, so a bare `Json` encoded a freshly constructed state as
 * literally `{}` - and a partially-default one as a fragment. That is fine when both ends share
 * the Kotlin classes and can fill the gaps back in; it is wrong here, because the premise of the
 * split-brain model is a host renderer that does **not** have the plugin's classes.
 *
 * Two concrete losses it caused:
 *
 * - The `runAvailable` / `persistenceAvailable` / `pageContextAvailable` flags the holders
 *   publish to declare what a child JVM cannot do are `false` by default, so they were dropped
 *   from the envelope in exactly the case they exist to report. A renderer could not tell
 *   "unavailable" from "not sent".
 * - Any catalog of display text carried alongside an enum selection - see
 *   `AtlasState.permissionModes` - is a constant, therefore always at its default, therefore
 *   never sent at all.
 *
 * Tests assert against this rather than a bare `Json`, so what they pin is what ships.
 */
internal val StateWireJson: Json = Json { encodeDefaults = true }
