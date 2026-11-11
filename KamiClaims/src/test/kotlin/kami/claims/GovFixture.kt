package kami.claims

import kami.claims.gov.PresetId
import kami.claims.gov.Presets

fun Country.mixed(): Country {
    Presets.found(this, PresetId.MIXED)
    return this
}
