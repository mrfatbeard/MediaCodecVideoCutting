package com.github.mrfatbeard.mediacodecvideocutting;

public class Assertion {
    public static void check(Condition condition) {
        if (!condition.evaluate()) {
            throw new AssertionError();
        }
    }

    public interface Condition {
        boolean evaluate();
    }
}
