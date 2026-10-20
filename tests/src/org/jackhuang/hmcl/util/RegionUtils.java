/*
 * Minimal stand-in for HMCL's RegionUtils.
 *
 * This class is used only by the agent smoke test. It deliberately contains
 * the same strings that RegionFixAgent looks for in its bytecode fallback.
 */

package org.jackhuang.hmcl.util;

public final class RegionUtils {

    public static boolean isChinaMainlandNetwork = computeNetwork();

    private static boolean computeNetwork() {
        String timezone = System.getProperty("regionfix.test.timezone", "");
        String country = System.getProperty("regionfix.test.country", "");
        return "Asia/Shanghai".equals(timezone) || "CN".equals(country);
    }

    private RegionUtils() {
    }
}
