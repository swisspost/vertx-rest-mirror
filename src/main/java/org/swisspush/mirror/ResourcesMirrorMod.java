package org.swisspush.mirror;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Initializes the ResourcesMirrorHandler.
 *
 * @author Florian Kammermann, Oliver Henning
 */
public class ResourcesMirrorMod extends AbstractVerticle {

    private static final Logger LOG = LoggerFactory.getLogger(ResourcesMirrorMod.class);

    private static final int DEFAULT_MAX_HEADER_SIZE = 64 * 1024;
    private static final int DEFAULT_MAX_INITIAL_LINE_LENGTH = 16 * 1024;

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

        JsonArray defaultRequestHeaders = config.getJsonArray("internalRequestHeaders");
        Map<String, String> internalRequestHeaders = buildInternalRequestHeaders(defaultRequestHeaders);

        final HttpClient selfClient = vertx.createHttpClient(buildHttpClientOptions(selfHost, selfPort, selfTimeout));
        final HttpClient mirrorClient = vertx.createHttpClient(buildHttpClientOptions(mirrorHost, mirrorPort, mirrorTimeout));

        // in Vert.x 2x 100-continues was activated per default, in vert.x 3x it is off per default.
        HttpServerOptions options = new HttpServerOptions()
                .setHandle100ContinueAutomatically(true)
                .setMaxHeaderSize(DEFAULT_MAX_HEADER_SIZE)
                .setMaxInitialLineLength(DEFAULT_MAX_INITIAL_LINE_LENGTH);

        vertx.createHttpServer(options).requestHandler(new ResourcesMirrorHandler(vertx, mirrorRootPath, mirrorClient,
                selfClient, internalRequestHeaders)).listen(serverPort, result -> {
            if (result.succeeded()) {
                startPromise.complete();
            } else {
                startPromise.fail(result.cause());
            }
        });
    }

    private Map<String, String> buildInternalRequestHeaders(JsonArray headersArray) {
        HashMap<String, String> headersMap = new HashMap<>();
        if(headersArray == null) {
            return headersMap;
        }

        for (int i = 0; i < headersArray.size(); i++) {
            try {
                JsonArray array = headersArray.getJsonArray(i);
                if(array.size() == 2) {
                    String headerName = array.getString(0);
                    String headerValue = array.getString(1);
                    if(headerName != null && headerValue != null) {
                        headersMap.put(headerName, headerValue);
                    }
                } else {
                    LOG.warn("Invalid configuration entry for internal request header: {}", array);
                }
            } catch (ClassCastException ex) {
                LOG.warn("Got invalid configuration resource for internal request headers. Not going to use it!");
            }
        }

        return headersMap;
    }

    private HttpClientOptions buildHttpClientOptions(String host, int port, int timeout) {
        return new HttpClientOptions()
                .setDefaultHost(host)
                .setDefaultPort(port)
                .setMaxPoolSize(25)
                .setKeepAlive(true)
                .setPipelining(false)
                .setConnectTimeout(timeout);
    }
}
