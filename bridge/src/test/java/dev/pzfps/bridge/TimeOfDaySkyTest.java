package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class TimeOfDaySkyTest {
    @Test
    void authoritativeNoonIsBrighterThanNight() {
        TimeOfDaySky noon = TimeOfDaySky.from(12, 6, 20, 0);
        TimeOfDaySky midnight = TimeOfDaySky.from(0, 6, 20, 1);
        assertTrue(luminance(noon.horizon()) > luminance(midnight.horizon()) * 8);
        assertTrue(luminance(noon.zenith()) > luminance(midnight.zenith()) * 8);
    }

    @Test
    void identicalPausedClockProducesIdenticalSky() {
        assertEquals(TimeOfDaySky.from(14.25f, 6, 20, .1f),
                TimeOfDaySky.from(14.25f, 6, 20, .1f));
    }

    @Test
    void dawnAddsWarmHorizonWithoutBrighteningMidnight() {
        TimeOfDaySky dawn = TimeOfDaySky.from(6, 6, 20, .35f);
        TimeOfDaySky midnight = TimeOfDaySky.from(0, 6, 20, 1);
        assertTrue(dawn.horizon().red() > dawn.horizon().blue());
        assertTrue(dawn.horizon().red() > midnight.horizon().red());
    }

    private static float luminance(TimeOfDaySky.Color value) {
        return value.red() * .2126f + value.green() * .7152f + value.blue() * .0722f;
    }
}
