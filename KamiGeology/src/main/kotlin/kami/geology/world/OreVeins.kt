package kami.geology.world

import kami.geology.config.ConfigStore

object OreVeins {
    @JvmStatic
    fun allow(vanilla: Boolean): Boolean = vanilla && ConfigStore.current?.general?.disableOreVeins != true
}
