package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.protocol.ProtocolPidIds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OBD-86: pins the locally-declared `:app` catalog id [ENGINE_LOAD_PID_ID] equal to its
 * `:core:protocol` counterpart, `ProtocolPidIds.ENGINE_LOAD`, so the two can never silently
 * drift — mirrors `SpeedCorrectionDataSourceTest`'s speed parity assertion (OBD-61). Lives in
 * `testProd` (not `test`) because `ProtocolPidIds` is only on the `prod` classpath — see
 * [ENGINE_LOAD_PID_ID]'s KDoc.
 */
class GaugeCatalogProdParityTest {
    @Test
    fun `the local engine load id matches the protocol layer's ProtocolPidIds_ENGINE_LOAD`() {
        assertEquals(ProtocolPidIds.ENGINE_LOAD, ENGINE_LOAD_PID_ID)
    }
}
