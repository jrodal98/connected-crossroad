package com.google.location.nearby.apps.connectedcrossroad;

import java.util.Random;

/** Utility class to generate random Android names */
public final class CodenameGenerator {
    private static final Random generator = new Random();

    /** Generate a random Android agent codename */
    public static String generate() {
        return String.valueOf(generator.nextInt());
    }
}
