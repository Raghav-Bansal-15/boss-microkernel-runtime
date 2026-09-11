package ai.rever.boss.plugin.runtime.stateholders;

public final class EnvironmentProbe {
    public static void main(String[] args) {
        String[] protectedNames = {"BOSS_PROCESS_TOKEN", "BOSS_KERNEL_TLS_CERT", "BOSS_IPC_TLS_CERT", "BOSS_IPC_TLS_KEY", "BOSS_HOST_TOKEN"};
        boolean leaked = false;
        for (String key : System.getenv().keySet()) {
            for (String protectedName : protectedNames) {
                if (key.equalsIgnoreCase(protectedName)) leaked = true;
            }
        }
        System.out.println((leaked ? "leaked:" : "clean:") + System.getenv("BOSS_TEST_NORMAL"));
    }
}
