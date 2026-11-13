package net.de5.rainbowcrrsys.folio;

/**
 * Entry point: {@code java -jar folio-0.1.0-SNAPSHOT.jar}
 *
 * <p>Startup order is deliberate: the license file is written first, then the EULA gate.
 * Until the EULA is accepted, no socket is bound, no config is generated, and no metrics
 * are written — the first start only leaves {@code 许可.txt} and {@code eula.txt} behind.
 */
public final class Folio {

    private Folio() {
    }

    public static void main(String[] args) throws Exception {
        License.ensure();
        if (!Eula.ensure()) {
            System.getLogger("folio").log(System.Logger.Level.INFO,
                    "Edit eula.txt to eula=true, then start folio again.");
            return;
        }

        ServerConfig cfg = ServerConfig.load();
        GcRuntime gc = GcRuntime.start(cfg);
        OnlineAuth online = new OnlineAuth(cfg);
        NioServer server = new NioServer(cfg, gc, online);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            gc.close();
        }, "folio-shutdown"));

        server.start();
        System.getLogger("folio").log(System.Logger.Level.INFO,
                "folio listening on port " + server.port()
                        + " (online-mode=" + cfg.onlineMode
                        + ", supported=26.1/26.2/26.3)");
        server.await();
    }
}
