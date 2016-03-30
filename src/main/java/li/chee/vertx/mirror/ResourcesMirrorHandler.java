package li.chee.vertx.mirror;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.ext.web.Router;

/**
 * GET a zip file and create a {@link li.chee.vertx.mirror.ZipFileEntryIterator}.
 * Iterates over Zip Entries (only files are considered) and PUT them into the mirror.
 *
 * @author: Florian Kammermann
 */
public class ResourcesMirrorHandler implements Handler<HttpServerRequest> {

    private Router router;
    private final Logger log;
    private final String mirrorRootPath;
    private final HttpClient mirrorHttpClient;
    private final HttpClient selfHttpClient;

    /**
     * Default constructor
     * 
     * @param log logger
     * @param mirrorRootPath the root path, that is used to get the zip and to put the resources
     * @param mirrorHttpClient where the verticle access the zip
     * @param selfHttpClient where the zip file entries are putted
     */
    public ResourcesMirrorHandler(Vertx vertx, final Logger log, final String mirrorRootPath, final HttpClient mirrorHttpClient, final HttpClient selfHttpClient) {
        this.log = log;
        this.mirrorRootPath = mirrorRootPath;
        this.mirrorHttpClient = mirrorHttpClient;
        this.selfHttpClient = selfHttpClient;

        this.router = Router.router(vertx);

        router.postWithRegex(".*mirror").handler(ctx -> ctx.request().bodyHandler(buffer -> {
            JsonObject body;
            try {
                body = new JsonObject(buffer.toString("UTF-8"));
            } catch (DecodeException e) {
                log.error("mirror - body is not valid json: " + buffer.toString("UTF-8"));
                ctx.response().setStatusCode(400);
                ctx.response().end("body is not valid json: " + buffer.toString("UTF-8"));
                return;
            }
            String path = body.getString("path");

            if (path == null) {
                log.error("mirror - the path attribute is missing");
                ctx.response().setStatusCode(400);
                ctx.response().end("the path attribute is missing");
                return;
            }
            // if the x-delta-sync attribute is available
            // the value is a path (relative) to the mirrorRootPath
            // which a x-delta value is stored.
            // this value has to be passed to the request as a
            // parameter (&delta=x).
            String xDeltaSync = body.getString("x-delta-sync");

            // content-type of the contents in zip file
            String contentType = body.getString("content-type");
            if (xDeltaSync != null) {
                performDeltaMirror(path, ctx.request(), xDeltaSync, contentType);
            } else {
                performMirror(path, ctx.request(), null, contentType);
            }
        }));
    }

    private void performDeltaMirror(final String path, final HttpServerRequest request, final String xDeltaSync, final String contentType) {
        final String xDeltaSyncPath = mirrorRootPath + "/" + xDeltaSync;
        final HttpClientRequest xDeltaSyncRequest = selfHttpClient.request(HttpMethod.GET, xDeltaSyncPath, xDeltaSyncResponse -> {
            xDeltaSyncResponse.bodyHandler(buffer -> {
                log.debug("mirror - handle the x-delta-sync response, statusCode:  " + xDeltaSyncResponse.statusCode() + " url: " + xDeltaSyncPath);

                int delta = 0;
                // found the file
                if (xDeltaSyncResponse.statusCode() == 200) {
                    JsonObject body = new JsonObject(buffer.toString("UTF-8"));
                    delta = body.getInteger("x-delta");
                }

                StringBuilder modifiedPath = new StringBuilder();
                modifiedPath.append(path);

                // does the path have parameters?
                if (path.lastIndexOf('?') != -1) {
                    modifiedPath.append("&");
                }
                // or not?
                else {
                    modifiedPath.append("?");
                }

                modifiedPath.append("delta=").append(delta);

                performMirror(modifiedPath.toString(), request, xDeltaSyncPath, contentType);
            });
        });

        log.debug("mirror - get x-delta-sync file: " + xDeltaSyncPath);
        xDeltaSyncRequest.end();
    }

    private void performMirror(String path, final HttpServerRequest request, final String xDeltaSyncPath, final String contentType) {
        final String mirrorPath = mirrorRootPath + "/mirror/" + path;
        final HttpClientRequest zipReq = mirrorHttpClient.request(HttpMethod.GET, mirrorPath, zipRes -> {
            zipRes.bodyHandler(buffer -> {
                log.debug("mirror - handle the zip file response, statusCode:  " + zipRes.statusCode() + " url: " + mirrorPath);
                if (zipRes.statusCode() == 200) {
                    log.debug("mirror - successfully got the zip file, create ZipFileEntryIterator");
                    ByteArrayInputStream is = new ByteArrayInputStream(buffer.getBytes());
                    ZipFileEntryIterator zipFileEntryIterator = new ZipFileEntryIterator(is, log);

                    final AtomicInteger entryRequestCounter = new AtomicInteger();
                    final JsonArray loadedResources = new JsonArray();
                    final AtomicBoolean success = new AtomicBoolean(true);

                    if (!zipFileEntryIterator.hasNext()) {
                        log.info("mirror - found no file entry in the zip file: " + mirrorPath);
                        success.set(false);

                        // in delta sync it's perfectly normal, that no zip entry could be found
                        sendResponse(success, loadedResources, "no zip entry found or no valid zip file", (xDeltaSyncPath != null ? 200 : 400), request);
                    }

                    final ZipFileEntryIterator finalZipFileEntryIterator = zipFileEntryIterator;

                    while (finalZipFileEntryIterator.hasNext()) {
                        Map<String, byte[]> zipFileEntry = finalZipFileEntryIterator.next();
                        final String relativePath = zipFileEntry.keySet().iterator().next();
                        final String absolutePath = mirrorRootPath + "/" + relativePath;
                        int openRequests = entryRequestCounter.incrementAndGet();
                        log.debug("mirror - mirror " + mirrorPath + " has open requests: " + openRequests);
                        log.debug("mirror - put resource: " + absolutePath);
                        final HttpClientRequest cReq = selfHttpClient.request(HttpMethod.PUT, absolutePath, cRes -> {
                            cRes.bodyHandler(data -> {
                                int openRequests1 = entryRequestCounter.decrementAndGet();
                                log.debug("mirror - mirror " + mirrorPath + " has open requests: " + openRequests1);
                                log.debug("mirror - mirror request was successfull for: " + relativePath);

                                Map<String, Object> loadedResourcesEntry = new HashMap<>();
                                loadedResourcesEntry.put("path", relativePath);
                                loadedResourcesEntry.put("success", true);
                                loadedResources.add(new JsonObject(loadedResourcesEntry));

                                if (!finalZipFileEntryIterator.hasNext() && openRequests1 == 0) {
                                    String xDeltaHeader = null;
                                    if (xDeltaSyncPath != null) {
                                        xDeltaHeader = zipRes.headers().get("x-delta");
                                        saveDeltaResponse(xDeltaSyncPath, xDeltaHeader, success, loadedResources, request);
                                    } else {
                                        sendResponse(success, loadedResources, null, 200, request);
                                    }
                                }
                            });
                            cRes.exceptionHandler(exception -> {
                                int openRequests1 = entryRequestCounter.decrementAndGet();
                                log.debug("mirror - mirror " + mirrorPath + " has open requests: " + openRequests1);
                                log.info("mirror - mirror request failed for: " + relativePath + " exception: " + exception.getMessage());

                                Map<String, Object> loadedResourcesEntry = new HashMap<>();
                                loadedResourcesEntry.put("path", relativePath);
                                loadedResourcesEntry.put("success", false);
                                loadedResources.add(new JsonObject(loadedResourcesEntry));

                                if (!finalZipFileEntryIterator.hasNext() && openRequests1 == 0) {
                                    success.set(false);
                                    sendResponse(success, loadedResources, exception.toString(), 400, request);
                                }
                            });
                        });
                        if(contentType != null) {
                            cReq.putHeader("Content-Type", contentType);
                        }

                        if (log.isTraceEnabled()) {
                            log.trace("mirror - set cReq headers");
                        }

                        cReq.exceptionHandler(e -> {
                            log.error("mirror - error in put request", e);
                            sendResponse(new AtomicBoolean(false), new JsonArray(), e.toString(), 500, request);
                        });

                        log.debug("mirror - put zip file entry: " + relativePath);
                        cReq.headers().set("Accept", "application/json");
                        cReq.headers().set("Content-length", String.valueOf(zipFileEntry.get(relativePath).length));
                        Buffer bufferZipFileEntry = Buffer.buffer(zipFileEntry.get(relativePath));
                        cReq.write(bufferZipFileEntry);
                        cReq.end();
                    }
                }

                if (zipRes.statusCode() != 200) {
                    log.error("mirror - couldn't get the resource: " + mirrorPath + " http status code was: " + zipRes.statusCode());
                    request.response().setStatusCode(zipRes.statusCode());
                    request.response().end("couldn't get the resource: " + mirrorPath);
                }

            });
        });

        log.debug("mirror - get zip file: " + mirrorPath);
        zipReq.end();
    }

    private void saveDeltaResponse(String xDeltaSyncPath, final String xDeltaHeader, final AtomicBoolean success, final JsonArray loadedResources, final HttpServerRequest request) {
        final HttpClientRequest xDeltaSyncRequest = selfHttpClient.request(HttpMethod.PUT, xDeltaSyncPath, response -> {
            if (response.statusCode() == 200) {
                sendResponse(success, loadedResources, null, 200, request);
            } else {
                success.set(false);
                sendResponse(success, loadedResources, response.statusMessage(), response.statusCode(), request);
            }
        });

        JsonObject requestPayload = new JsonObject();

        int xDelta = 0;
        try {
            xDelta = Integer.parseInt(xDeltaHeader);
        } catch (NumberFormatException e) {
            log.debug("mirror - could not parse x-delta response: " + xDeltaHeader);
        }

        log.debug("mirror - put x-delta-sync file: " + xDeltaSyncPath);
        requestPayload.put("x-delta", xDelta);
        Buffer payload = Buffer.buffer(requestPayload.encodePrettily());
        xDeltaSyncRequest.headers().set("Content-Type", "application/json; charset=utf-8");
        xDeltaSyncRequest.headers().set("Content-Length", "" + payload.length());
        xDeltaSyncRequest.setChunked(false);
        xDeltaSyncRequest.write(payload);
        xDeltaSyncRequest.end();
    }

    private static void sendResponse(AtomicBoolean success, JsonArray loadedResources, String reason, int httpStatusCode, HttpServerRequest request) {
        JsonObject responsePayload = new JsonObject();
        responsePayload.put("success", success.get());
        if (reason != null) {
            responsePayload.put("reason", reason);
        }
        responsePayload.put("loadedresources", loadedResources);
        Buffer payload = Buffer.buffer(responsePayload.encodePrettily());
        request.response().setStatusCode(httpStatusCode);
        request.response().headers().add("Content-Type", "application/json; charset=utf-8");
        request.response().headers().add("Content-Length", "" + payload.length());
        request.response().setChunked(false);
        request.response().write(payload);
        request.response().end();
    }

    @Override
    public void handle(HttpServerRequest request) {
        router.accept(request);
    }

}
