package com.github.mrfatbeard.mediacodecvideocutting;

public class StringUtils {
    public static String getFfmpegTime(long millis) {
        long msecs = millis;
        long hrs = msecs / 1000 / 60 / 60;
        msecs -= hrs * 1000 * 60 * 60;
        long mins = msecs / 1000 / 60;
        msecs -= mins * 1000 * 60;
        long secs = msecs / 1000;
        msecs -= secs * 1000;
        return String.format("%02d:%02d:%02d.%03d", hrs, mins, secs, msecs);
    }
}
