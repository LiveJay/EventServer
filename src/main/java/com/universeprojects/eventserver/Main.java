package com.universeprojects.eventserver;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;

import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {
    private final Logger log = Logger.getGlobal();
    private static final String CONFIG_CLUSTERED = "clustered";

    public static void main(String[] args) {
        new Main().run(args);
    }

    @SuppressWarnings("UnusedParameters")
    public void run(String[] args) {
        boolean clustered = Config.getBoolean(CONFIG_CLUSTERED, false);
        VertxOptions options = createVertxOptions();
        if(clustered) {
            options.setClustered(true);
            com.hazelcast.config.Config hazelCastConfig = new com.hazelcast.config.Config();
            //TODO: configure
            HazelcastClusterManager clusterManager = new HazelcastClusterManager(hazelCastConfig);
            options.setClusterManager(clusterManager);
            Vertx.clusteredVertx(options, (res) -> {
                if (res.succeeded()) {
                    Vertx vertx = res.result();
                    deployVerticle(vertx);
                } else {
                    log.log(Level.SEVERE, "Error starting clustered Vertx", res.cause());
                }
            });
        } else {
            options.setClustered(false);
            Vertx vertx = Vertx.vertx(options);
            deployVerticle(vertx);
        }
    }

    private void deployVerticle(Vertx vertx) {
        vertx.deployVerticle(new EventServerVerticle());
    }

    private VertxOptions createVertxOptions() {
        return new VertxOptions();
    }

}