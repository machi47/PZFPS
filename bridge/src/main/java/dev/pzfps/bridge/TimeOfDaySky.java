package dev.pzfps.bridge;

/** Immutable procedural-sky inputs derived only from PZ's authoritative clock. */
record TimeOfDaySky(float timeOfDay, Color horizon, Color zenith) {
    record Color(float red, float green, float blue) {}

    static TimeOfDaySky from(float timeOfDay, float dawn, float dusk, float nativeNight) {
        float hour = wrap24(timeOfDay);
        float dayRise = smoothstep(dawn - .75f, dawn + 1.25f, hour);
        float daySet = 1.0f - smoothstep(dusk - 1.25f, dusk + .75f, hour);
        float daylight = clamp(dayRise * daySet);
        float night = clamp(Math.max(nativeNight, 1.0f - daylight));
        float twilight = Math.max(
                1.0f - wrappedDistance(hour, dawn) / 1.8f,
                1.0f - wrappedDistance(hour, dusk) / 1.8f);
        twilight = clamp(twilight) * (1.0f - night * .55f);

        Color nightHorizon = new Color(.012f, .019f, .045f);
        Color dayHorizon = new Color(.48f, .68f, .88f);
        Color warmHorizon = new Color(.92f, .33f, .13f);
        Color nightZenith = new Color(.002f, .006f, .018f);
        Color dayZenith = new Color(.10f, .34f, .68f);
        Color horizon = mix(mix(dayHorizon, nightHorizon, night), warmHorizon, twilight * .62f);
        Color zenith = mix(mix(dayZenith, nightZenith, night),
                new Color(.25f, .12f, .16f), twilight * .24f);
        return new TimeOfDaySky(hour, horizon, zenith);
    }

    private static Color mix(Color a, Color b, float amount) {
        float t = clamp(amount);
        return new Color(
                a.red + (b.red - a.red) * t,
                a.green + (b.green - a.green) * t,
                a.blue + (b.blue - a.blue) * t);
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = clamp((value - edge0) / (edge1 - edge0));
        return t * t * (3.0f - 2.0f * t);
    }

    private static float wrap24(float value) {
        float result = value % 24.0f;
        return result < 0 ? result + 24.0f : result;
    }

    private static float wrappedDistance(float a, float b) {
        float distance = Math.abs(wrap24(a) - wrap24(b));
        return Math.min(distance, 24.0f - distance);
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
