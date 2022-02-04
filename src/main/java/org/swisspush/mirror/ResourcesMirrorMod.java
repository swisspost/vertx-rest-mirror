package org.swisspush.mirror;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;

/**
 * Initializes the ResourcesMirrorHandler.
 *
 * @author Florian Kammermann, Oliver Henning
 */
public class ResourcesMirrorMod extends AbstractVerticle {

    @Override
    public void start(Promise<Void> startPromise) {

        JsonObject config = config();

        // the port where the http server is listen on
        int serverPort = config.getInteger("serverPort", 8686);

        // the host and port where the ResourceMirrorHandler will put the resources from within the zip file
        String selfHost = config.getString("selfClientHost", "localhost");
        int selfPort = config.getInteger("selfClientPort", 7012);
        int selfTimeout = config.getInteger("selfClientTimeout", 30000);

        // the host and port where the ResourceMirrorHandler will access the zip file
        String mirrorHost = config.getString("mirrorHost", "localhost");
        int mirrorPort = config.getInteger("mirrorPort", 7012);
        int mirrorTimeout = config.getInteger("selfClientTimeout", 30000);

        // the root path used for accessing the zip file
        String mirrorRootPath = config.getString("mirrorRootPath", "/root");

        final HttpClient selfClient = vertx.createHttpClient(buildHttpClientOptions(selfHost, selfPort, selfTimeout));
        final HttpClient mirrorClient = vertx.createHttpClient(buildHttpClientOptions(mirrorHost, mirrorPort, mirrorTimeout));

        // in Vert.x 2x 100-continues was activated per default, in vert.x 3x it is off per default.
        HttpServerOptions options = new HttpServerOptions().setHandle100ContinueAutomatically(true);

        vertx.createHttpServer(options).requestHandler(new ResourcesMirrorHandler(vertx, mirrorRootPath, mirrorClient, selfClient)).listen(serverPort, result -> {
            if(result.succeeded()){
                startPromise.complete();
            } else {
                startPromise.fail(result.cause());
            }
        });
    }

    private HttpClientOptions buildHttpClientOptions(String host, int port, int timeout){
        return new HttpClientOptions()
                .setDefaultHost(host)
                .setDefaultPort(port)
                .setMaxPoolSize(25)
                .setKeepAlive(true)
                .setPipelining(false)
                .setConnectTimeout(timeout);
    }
}
