package scaffolding;

public class RustTestHelper {
    public static boolean isRustMode() {
        return Boolean.getBoolean("cranker.router.rust") || "true".equalsIgnoreCase(System.getenv("CRANKER_ROUTER_RUST"));
    }

    public static boolean isTlsMode() {
        return Boolean.getBoolean("cranker.router.tls") || "true".equalsIgnoreCase(System.getenv("CRANKER_TLS")) || "true".equalsIgnoreCase(System.getenv("CRANKER_ROUTER_TLS"));
    }
}
