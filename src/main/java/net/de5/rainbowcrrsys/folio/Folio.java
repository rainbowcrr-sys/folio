package net.de5.rainbowcrrsys.folio;

/** Entry point: java -jar folio-0.1.0-SNAPSHOT.jar */
public final class Folio {

    private Folio() {
    }

    public static void main(String[] args) throws Exception {
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
