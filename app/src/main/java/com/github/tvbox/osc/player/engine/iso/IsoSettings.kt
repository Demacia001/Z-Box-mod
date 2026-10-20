package com.github.tvbox.osc.player.engine.iso

import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV

object IsoSettings {

    fun lpcmEnabled(): Boolean = KV.get(HawkConfig.ISO_LPCM, HawkConfig.ISO_LPCM_DEFAULT)

    fun setLpcmEnabled(value: Boolean) {
        KV.put(HawkConfig.ISO_LPCM, value)
    }

    fun trueHdCoreEnabled(): Boolean = KV.get(HawkConfig.ISO_TRUEHD_CORE, HawkConfig.ISO_TRUEHD_CORE_DEFAULT)

    fun setTrueHdCoreEnabled(value: Boolean) {
        KV.put(HawkConfig.ISO_TRUEHD_CORE, value)
    }

    fun pgsEnabled(): Boolean = KV.get(HawkConfig.ISO_PGS, HawkConfig.ISO_PGS_DEFAULT)

    fun setPgsEnabled(value: Boolean) {
        KV.put(HawkConfig.ISO_PGS, value)
    }
}
