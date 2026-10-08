package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.VehicleGear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Lynk09InstrumentReadingTest {
    @Test fun parsesCarsBridgeInstrumentResult() {
        assertEquals(
            Lynk09InstrumentReading(VehicleGear.PARK, 0.0),
            Lynk09InstrumentReading.parse("成功 仪表信号读取完成\ngear=P\nspeed=0.0 km/h\n"),
        )
        assertEquals(
            Lynk09InstrumentReading(VehicleGear.DRIVE, 42.7),
            Lynk09InstrumentReading.parse("成功 仪表信号读取完成\r\ngear=3\r\nspeed=42.7 km/h\r\n"),
        )
    }

    @Test fun skipsFailedIncompleteAndImplausibleReadings() {
        assertNull(Lynk09InstrumentReading.parse("失败 仪表信号读取失败\ngear=D\nspeed=21.0 km/h"))
        assertNull(Lynk09InstrumentReading.parse("成功 仪表信号读取完成\ngear=UNKNOWN\nspeed=21.0 km/h"))
        assertNull(Lynk09InstrumentReading.parse("成功 仪表信号读取完成\ngear=D\nspeed=UNKNOWN"))
        assertNull(Lynk09InstrumentReading.parse("成功 仪表信号读取完成\ngear=D\nspeed=301.0 km/h"))
    }
}
