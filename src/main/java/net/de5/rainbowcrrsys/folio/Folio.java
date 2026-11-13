package net.de5.rainbowcrrsys.folio;

/**
 * Entry point: {@code java -jar folio.jar}
 *
 * <p>Startup order mirrors a vanilla dedicated server: logs and the on-disk server layout
 * are prepared first, then the EULA gate. Until the EULA is accepted no socket is bound and
 * no config or metrics are written.
 *
 * <p>After acceptance the server generates {@code config.yaml} if missing, starts the
 * metrics writer, binds the port, and stays up until the process is stopped.
 */
public final class Folio {

    private Folio() {
    }

    public static void main(String[] args) throws Exception {
        LogFiles.install();
        License.ensure();
        ServerFiles.ensure();

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
