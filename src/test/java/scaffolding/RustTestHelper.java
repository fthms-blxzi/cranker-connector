package scaffolding;

public class RustTestHelper {
    public static boolean isRustMode() {
        return Boolean.getBoolean("cranker.router.rust") || "true".equalsIgnoreCase(System.getenv("CRANKER_ROUTER_RUST"));
    }
}
