package org.swisspush.mirror;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.*;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <ul>
 *     <li>receives and parses MirrorRequest</li>
 *     <li>starts a new instance of {@link MirrorRequestHandler} for each MirrorRequest</li>
 * </ul>
 *
 * @author Florian Kammermann, Mario Aerni, Oliver Henning
 */
public class ResourcesMirrorHandler implements Handler<HttpServerRequest> {

    private static final Logger LOG = LoggerFactory.getLogger(ResourcesMirrorHandler.class);

    private final Router router;

    /**
     * Default constructor
     *
     * @param vertx vertx
     * @param mirrorRootPath the root path, that is used to get the zip and to put the resources
     * @param mirrorHttpClient where the verticle access the zip
     * @param selfHttpClient where the zip file entries are putted
     */
    ResourcesMirrorHandler(Vertx vertx, String mirrorRootPath, HttpClient mirrorHttpClient, HttpClient selfHttpClient) {
        this.router = Router.router(vertx);

        router.postWithRegex(".*mirror").handler(ctx -> ctx.request().bodyHandler(buffer -> {
            HttpServerResponse response = ctx.response();

            JsonObject body;
            try {
                body = buffer.toJsonObject();
            } catch (DecodeException e) {
                LOG.error("mirror - body is not valid json: " + buffer.toString());
                response
                        .setChunked(true)
                        .setStatusCode(HttpResponseStatus.BAD_REQUEST.code())
                        .end("body is not valid json: " + buffer.toString());
                return;
            }
            String path = body.getString("path");

            if (path == null) {
                LOG.error("mirror - the path attribute is missing");
                response
                        .setChunked(true)
                        .setStatusCode(HttpResponseStatus.BAD_REQUEST.code())
                        .end("the path attribute is missing");
                return;
            }
            // if the x-delta-sync attribute is available
            // the value is a path (relative) to the mirrorRootPath
            // which a x-delta value is stored.
            // this value has to be passed to the request as a
            // parameter (&delta=x).
            String xDeltaSync = body.getString("x-delta-sync");

            new MirrorRequestHandler(selfHttpClient, mirrorHttpClient, response, mirrorRootPath).perform(path, xDeltaSync);
        }));
    }

    @Override
    public void handle(HttpServerRequest request) {
        router.handle(request);
    }

}
