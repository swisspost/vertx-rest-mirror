package org.swisspush.mirror;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.mirror.util.MimeTypeResolver;

import java.util.Map;

/**
 * Visits a ZipInputStream (wrapped in a {@link ZipIterator}) entry by entry
 * and executes a http-PUT with its content for each entry.
 * <br><br>
 * Implementation notes:
 * <ul>
 *     <li>handles entries one after another to avoid peak load for huge ZIPs</li>
 *     <li>keeps a log for all processed ZIP-entries, incl. a success-indicator for each entry</li>
 *     <li>continues processing even if a PUT fails</li>
 *     <li>... but stops processing if we get sudden exceptions, e.g. from corrupt ZIP-stream</li>
 * </ul>
 */
public class ZipEntryPutter {

    private final static Logger LOG = LoggerFactory.getLogger(ZipEntryPutter.class);

    private static final MimeTypeResolver MIME_TYPE_RESOLVER = new MimeTypeResolver(HttpHeaderValues.APPLICATION_JSON.toString());

    private final HttpClient httpClient;
    private final String mirrorRootPath;
    private final ZipIterator zipIterator;
    private Handler<AsyncResult<Void>> doneHandler;

    /**
     * a 'log' for all processed ZIP-entries
     */
    final JsonArray loadedResources = new JsonArray();
    private boolean success = true;

    private final Map<String, String> internalRequestHeaders;

    public ZipEntryPutter(HttpClient httpClient, String mirrorRootPath, ZipIterator zipIterator, Map<String, String> internalRequestHeaders) {
        this.httpClient = httpClient;
        this.mirrorRootPath = mirrorRootPath;
        this.zipIterator = zipIterator;
        this.internalRequestHeaders = internalRequestHeaders;
    }

    public void doneHandler(Handler<AsyncResult<Void>> doneHandler) {
        this.doneHandler = doneHandler;
    }


    private void callDoneHandler(Future<Void> future) {
        // ensure we only call it once
        if (doneHandler != null) {
            doneHandler.handle(future);
        }
        doneHandler = null;
    }

    public void handleNext() {
        UnzippedResource unzippedResource;
        try {
            if (zipIterator.hasNext()) {
                unzippedResource = zipIterator.next();
            } else {
                if (success) {
                    callDoneHandler(Future.succeededFuture());
                } else {
                    callDoneHandler(Future.failedFuture("at least one resources was not successful"));
                }
                return;
            }
        } catch (Exception ex) {
            LOG.error("Exception occured", ex);
            callDoneHandler(Future.failedFuture(ex));
            return;
        }

        final String relativePath = unzippedResource.filename;
        final String absolutePath = mirrorRootPath + "/" + relativePath;
        LOG.debug("mirror - put resource: {}", absolutePath);
        httpClient.request(HttpMethod.PUT, absolutePath).onComplete(event -> {
            HttpClientRequest cReq = event.result();
            handleInternalRequestHeaders(cReq);
            cReq.exceptionHandler(ex -> {
                LOG.error("mirror - error in put request for {}", relativePath, ex);
                addLoadedResourceInfo(relativePath, false);
                callDoneHandler(Future.failedFuture(ex));
            });

            String mimeType = MIME_TYPE_RESOLVER.resolveMimeType(relativePath);
            LOG.debug("mirror - put zip file entry: {}", relativePath);
            cReq.putHeader(HttpHeaders.CONTENT_TYPE, mimeType)
                    .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(unzippedResource.buffer.length()))
                    .end(unzippedResource.buffer);
            cReq.send(asyncResult -> {
                HttpClientResponse cRes = asyncResult.result();
                LOG.debug("mirror - PUT request {} finished with statusCode {}", relativePath, cRes.statusCode());

                // every succeeded and failed result must be stored
                int statusCodeGroup = cRes.statusCode() / 100;
                addLoadedResourceInfo(relativePath, statusCodeGroup == 2);

                cRes.exceptionHandler(ex -> {
                    LOG.error("mirror - error in put request for {}", relativePath, ex);
                    addLoadedResourceInfo(relativePath, false);
                    callDoneHandler(Future.failedFuture(ex));
                });

                cRes.endHandler(end -> {
                    handleNext();
                });
            });
        });

    }

    private void handleInternalRequestHeaders(HttpClientRequest request){
        if(internalRequestHeaders != null) {
            request.headers().addAll(internalRequestHeaders);
        }
    }

    private void addLoadedResourceInfo(String relativePath, boolean success) {
        LOG.debug("result fo {}: success={}", relativePath, success);
        if (!success) {
            // if one failed then let the whole ZIP handling fail
            this.success = false;
        }
        JsonObject loadedResource = new JsonObject()
                .put("path", relativePath)
                .put("success", success);
        loadedResources.add(loadedResource);
    }

}
