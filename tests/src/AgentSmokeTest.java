import org.jackhuang.hmcl.util.RegionUtils;

/**
 * Verifies that the java agent flips {@code isChinaMainlandNetwork} to false.
 *
 * Usage: AgentSmokeTest &lt;expected-value&gt;
 */
public final class AgentSmokeTest {

    public static void main(String[] args) {
        boolean expected = args.length > 0 && Boolean.parseBoolean(args[0]);
        boolean actual = RegionUtils.isChinaMainlandNetwork;

        if (actual != expected) {
            System.err.println("FAIL: isChinaMainlandNetwork=" + actual
                    + " (expected " + expected + ")");
            System.exit(1);
        }
        System.out.println("PASS: isChinaMainlandNetwork=" + actual);
    }

    private AgentSmokeTest() {
    }
}
