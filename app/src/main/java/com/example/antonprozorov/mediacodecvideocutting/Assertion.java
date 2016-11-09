package com.example.antonprozorov.mediacodecvideocutting;

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
