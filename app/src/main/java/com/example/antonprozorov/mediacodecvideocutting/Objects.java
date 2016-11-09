package com.example.antonprozorov.mediacodecvideocutting;

public final class Objects {
    public static <T> T requireNonNull(T obj) {
        if (obj == null)
            throw new NullPointerException();
        return obj;
    }
}
