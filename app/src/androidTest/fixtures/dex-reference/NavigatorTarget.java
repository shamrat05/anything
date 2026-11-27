package dev.anything.dexfixture;

public final class NavigatorTarget {
    public static int number = 4;
    public static int picked(int value) { return value + number; }
    public static int picked(String value) { return value.length(); }
}
