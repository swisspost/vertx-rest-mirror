package org.swisspush.mirror;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handle ONE MirrorRequest
 */
class MirrorRequestHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MirrorRequestHandler.class);

    private final static Pattern DELTA_PARAMETER_PATTERN = Pattern.compile("delta=([^&]+)");

    private final HttpClient selfHttpClient;
    private final HttpClient mirrorHttpClient;
    private final HttpServerResponse response;
    private final String mirrorRootPath;

    /**
     * null when it's a normal (i.e. no-delta) request
     * Otherwise its the path of a resource which stores our last known x-delta value
     */
    private String xDeltaSyncPath = null;

    /**
     * for delta-request this contains our last known delta-value (read from xDeltaSyncPath)
     */
    private long currentDelta = 0;

    private long newDelta = 0;

    private ZipEntryPutter zipEntryPutter;

    MirrorRequestHandler(HttpClient selfHttpClient, HttpClient mirrorHttpClient, HttpServerResponse response, String mirrorRootPath) {
        this.selfHttpClient = selfHttpClient;
        this.mirrorHttpClient = mirrorHttpClient;
        this.response = response;
        this.mirrorRootPath = mirrorRootPath;
    }


    public void perform(String path, String xDeltaSync) {
        if (xDeltaSync != null) {
            xDeltaSyncPath = mirrorRootPath + "/" + xDeltaSync;
            performDeltaMirror(path);
        } else {
            performMirror(path);
        }
    }

    private void performDeltaMirror(String path) {
        LOG.debug("mirror - get x-delta-sync resource: {}", xDeltaSyncPath);
        selfHttpClient.get(xDeltaSyncPath, getCurrentDeltaResponse -> {
            getCurrentDeltaResponse.bodyHandler(buffer -> {
                LOG.trace("mirror - handle the x-delta-sync response, statusCode: {} url: {}", getCurrentDeltaResponse.statusCode(), xDeltaSyncPath);

                // if found the file then extract the current (i.e. our latest received) delta value
                if (getCurrentDeltaResponse.statusCode() == HttpResponseStatus.OK.code()) {
                    JsonObject body = buffer.toJsonObject();
                    try {
                        currentDelta = body.getLong("x-delta");
                    } catch (Exception ex) {
                        // e.g. ClassCastException  could be raised if somebody fiddled around with the x-delta resource (i.e. x-delta attribute is a string or so)
                        LOG.warn("Problem reading delta as integer from {} (content is '{}'). We ignore this and assume a delta=0", xDeltaSyncPath, body, ex);
                        currentDelta = 0;
                    }
                }


                String pathWithDelta = path;
                // does the path have parameters?
                if (path.lastIndexOf('?') != -1) {
                    pathWithDelta += "&";
                }
                // or not?
                else {
                    pathWithDelta += "?";
                }

                pathWithDelta += "delta=" + currentDelta;

                performMirror(pathWithDelta);
            });
        }).end();
    }

    private void performMirror(String path) {
        String mirrorPath = mirrorRootPath + "/mirror/" + path;

        LOG.debug("mirror - get zip file: {}", mirrorPath);
        mirrorHttpClient.get(mirrorPath, zipResponse -> {

            if (zipResponse.statusCode() != 200) {
                LOG.error("mirror - couldn't get the resource: {} http status code was: {}", mirrorPath, zipResponse.statusCode());
                response
                        .setChunked(true)
                        .setStatusCode(zipResponse.statusCode())
                        .end("couldn't get the ZIP: " + mirrorPath);
                return;
            }

            if (xDeltaSyncPath != null) {
                String xDeltaString = zipResponse.getHeader("x-delta");
                try {
                    newDelta = Long.parseLong(xDeltaString);
                } catch (Exception ex) {
                    LOG.error("no or wrong response header 'x-delta: {}' received from {}", xDeltaString, mirrorPath, ex);
                    response
                            .setChunked(true)
                            .setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code())
                            .end("no or wrong response header 'x-delta: " + xDeltaString + "'received from " + mirrorPath);
                    return;
                }

                /*
                 * If the current delta value is higher
                 * then the returned xDelta, xDelta is
                 * reset to 0 and a re-request is performed,
                 * to guarantee to get all the needed
                 * elements.
                 */
                if (currentDelta > newDelta) {
                    LOG.warn("mirror - returned x-delta {} is lower then current x-delta value {}", newDelta, currentDelta);

                    // in order to get all data, we perform a retry with xDelta = 0
                    currentDelta = newDelta = 0;
                    LOG.info("mirror - starting a retry with x-delta = 0");
                    String pathWithDeltaZero = replaceInvalidDeltaParameter(path, 0);
                    performMirror(pathWithDeltaZero);
                    return;
                }
            }

            // hmmm - by using bodyHandler we get whole ZIP in-memory. No streaming. Will have memory issues when consuming huge ZIPs
            zipResponse.bodyHandler(buffer -> {
                LOG.debug("mirror - handle the zip file response, statusCode: {} url: {}", zipResponse.statusCode(), mirrorPath);
                BufferWrapperInputStream bwis = new BufferWrapperInputStream(buffer);
                ZipIterator zipIterator = new ZipIterator(bwis);

                boolean isEmptyZip;
                try {
                    isEmptyZip = !zipIterator.hasNext();
                } catch (Exception ex) {
                    LOG.error("Problem parsing the ZIP response for url {}", mirrorPath, ex);
                    sendResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR.code(), ex.getMessage());
                    return;
                }
                if (isEmptyZip) {
                    LOG.info("mirror - found no file entry in the zip file: {}", mirrorPath);
                    // in delta sync it's perfectly normal, that no zip entry could be found
                    boolean success = xDeltaSyncPath != null;
                    sendResponse(success ? 200 : 400,  "no zip entry found or no valid zip file");
                    return;
                }

                zipEntryPutter = new ZipEntryPutter(selfHttpClient, mirrorRootPath, zipIterator);
                zipEntryPutter.doneHandler(done -> {
                    /*
                     * If all the PUTs were successful and only then, the deltasync
                     * attribute may be written.
                     * Otherwise no delta sync is written!ZipContentHandlerTest
                     */
                    if (done.succeeded()) {
                        if (xDeltaSyncPath != null) {
                            saveNewDeltaAndThenSendResponse();
                        } else {
                            sendResponse(HttpResponseStatus.OK.code(), null);
                        }
                    } else {
                        LOG.error("ZipEntryPutter failed while working on ZIP from {}", mirrorPath, done.cause());
                        sendResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR.code(), done.cause().getMessage());
                    }
                });
                zipEntryPutter.handleNext();
            });
        }).exceptionHandler(ex -> {
            LOG.error("Exception occured in Mirror-Get-Request to {}", mirrorPath, ex);
            sendResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR.code(), ex.toString());
        }).end();
    }

    /**
     * Replaces the delta parameter
     * with another valid number.
     * If the pattern could not be found, the original path is returned instead.
     *
     * @param path the original path
     * @param delta the valid delta value
     * @return the new path with a valid delta value
     */
    protected String replaceInvalidDeltaParameter(String path, long delta) {
        Matcher matcher = DELTA_PARAMETER_PATTERN.matcher(path);
        if (matcher.find()) {
            return matcher.replaceAll("delta=" + delta);
        }

        return path;
    }

    private void saveNewDeltaAndThenSendResponse() {
        JsonObject body = new JsonObject();
        body.put("x-delta", newDelta);
        Buffer payload = body.toBuffer();

        LOG.debug("mirror - put x-delta-sync file: {} with value: {}", xDeltaSyncPath, newDelta);
        selfHttpClient
                .put(xDeltaSyncPath, putNewDeltaResponse -> {
                    int s = putNewDeltaResponse.statusCode();
                    if (s == 200) {
                        sendResponse(s, null);
                    } else {
                        sendResponse(s, putNewDeltaResponse.statusMessage());
                    }
                })
                .putHeader(HttpHeaders.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(payload.length()))
                .end(payload);
    }

    private void sendResponse(int responseStatusCode, String reason) {
        JsonObject body = new JsonObject();
        body.put("success", HttpResponseStatus.OK.code() == responseStatusCode);
        if (reason != null) {
            body.put("reason", reason);
        }
        if (zipEntryPutter != null) {
            body.put("loadedresources", zipEntryPutter.loadedResources);
        }
        Buffer payload = body.toBuffer();

        response
                .setStatusCode(responseStatusCode)
                .putHeader(HttpHeaders.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(payload.length()))
                .end(payload);

        if (LOG.isDebugEnabled()){
            LOG.debug("Content is: " );
            LOG.debug(Json.encodePrettily(body));
        }
    }

}
