package cloud.wafflecommons.pixelbrainreader.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** 13.2: the forecast's WMO code to the sky the weather card draws. */
class ExpressiveWeatherTest {

    @Test
    fun `each WMO code family has its sky`() {
        assertEquals(WeatherKind.CLEAR, weatherKindOf(0))
        assertEquals(WeatherKind.PARTLY_CLOUDY, weatherKindOf(2))
        assertEquals(WeatherKind.CLOUDY, weatherKindOf(3))
        assertEquals(WeatherKind.FOG, weatherKindOf(48))
        assertEquals(WeatherKind.RAIN, weatherKindOf(53))
        assertEquals(WeatherKind.RAIN, weatherKindOf(81))
        assertEquals(WeatherKind.SNOW, weatherKindOf(75))
        assertEquals(WeatherKind.SNOW, weatherKindOf(86))
        assertEquals(WeatherKind.STORM, weatherKindOf(95))
        // Anything else (an unknown or a future code) is a cloudy sky, never a crash.
        assertEquals(WeatherKind.CLOUDY, weatherKindOf(-2))
        assertEquals(WeatherKind.CLOUDY, weatherKindOf(150))
    }
}
