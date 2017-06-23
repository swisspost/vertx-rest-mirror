package org.swisspush.mirror;


import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.junit.*;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Test class for the ResourceMirrorHandler.
 *
 * @author: Mario Aerni
 */
@RunWith(VertxUnitRunner.class)
public class ResourceMirrorHandlerTest {
    private static final Logger log = LoggerFactory.getLogger(ResourceMirrorHandlerTest.class);
    private static final Map<String, Boolean> entries = new HashMap<>();

    private static Vertx vertx;
    private static int TARGET_PORT = 7012;
    private static int SOURCE_PORT = 7013;
    private static int SERVER_PORT = 8686;
    private static HttpServer sourceServer;
    private static HttpServer targetServer;


    @BeforeClass
    public static void setupBeforeClass(TestContext context) {
        Async async = context.async();

        // the zip entries (must match the file)
        entries.put("test_mirror/t1.json", false);
        entries.put("test_mirror/t1/browser.css", false);
        entries.put("test_mirror/t2/t21/test.html", false);
        entries.put("test_mirror/t2/t22/t31/loader.gif", false);
        entries.put("test_mirror/t2/t22/t31/test.png", false);


        vertx = Vertx.vertx();
        JsonObject mirrorConfig = new JsonObject();
        mirrorConfig.put("selfClientPort", TARGET_PORT);
        mirrorConfig.put("mirrorRootPath", "");
        mirrorConfig.put("serverPort", SERVER_PORT);
        mirrorConfig.put("mirrorPort", SOURCE_PORT);
        DeploymentOptions mirrorOptions = new DeploymentOptions().setConfig(mirrorConfig);

        // deploy verticle (mirror)
        vertx.deployVerticle(ResourcesMirrorMod.class.getName(), mirrorOptions, mirrorDeployEvent -> {
            // server ready
            async.complete();
        });

    }

    @After
    public void closeServer() {
        if ( targetServer != null ) {
            targetServer.close();
        }

        if ( sourceServer != null ) {
            sourceServer.close();
        }
    }


    /**
     * Creates a new localhost server listening on the given port and using the
     * given handler.
     *
     * @param port port of the server
     * @param handlerFunction the handler
     */
    private static HttpServer createServer(int port, Function<HttpServerRequest,Void> handlerFunction) {
        HttpServerOptions options = new HttpServerOptions();
        options.setHandle100ContinueAutomatically(true);
        options.setHost("localhost");
        options.setPort(port);
        HttpServer server = vertx.createHttpServer(options);
        server.requestHandler(handlerFunction::apply);
        server.listen();
        return server;
    }

    /**
     * Performs a mirror request to the server.
     *
     * @param path - the path which should be requested
     * @param testFunction - the function containing the handler
     * @param deltaPath - the path to the deltasync resource
     */
    private void performMirrorRequest(String path, Function<HttpClientResponse,Void> testFunction, String deltaPath) {
        JsonObject object = new JsonObject();
        object.put("path", path);
        if ( deltaPath != null ) {
            object.put("x-delta-sync", deltaPath );
        }

        String content = object.toString();
        String length = Integer.toString(content.length());

        vertx.createHttpClient().post(SERVER_PORT, "localhost", "/mirror")
                .putHeader("Content-Type", "application/json")
                .putHeader("Content-Length", length)
                .handler(testFunction::apply)
                .write(content, "UTF-8")
                .end();
    }

    /**
     * Writes the zip stream from the given path as a response to the given request.
     *
     * @param request incoming request
     * @param path path for the zip
     */
    private void writeZipStream(final HttpServerRequest request, final String path) {
        try ( InputStream in = this.getClass().getClassLoader().getResourceAsStream(path)) {
            Buffer buffer = Buffer.buffer(IOUtils.toByteArray(in));
            request.response().setChunked(false);
            request.response().headers().add("Content-Length", String.valueOf(buffer.length()));
            request.response().write(buffer);
            request.response().setStatusCode(HttpStatus.SC_OK);
        }
        catch( Exception e ) {
            request.response().setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR);
            System.err.println("Error: " + e.getMessage() );
        }
    }

    /**
     * Writes the GET response for the delta request.
     *
     * @param request request
     * @param currentDelta current delta
     */
    private void writeGETDeltaResponse(final HttpServerRequest request, final int currentDelta) {
        JsonObject content = new JsonObject();
        content.put("x-delta", currentDelta);
        Buffer buffer = Buffer.buffer(content.toString());
        request.response().setChunked(false);
        request.response().headers().add("Content-Length", String.valueOf(buffer.length()));
        request.response().headers().add("Content-Type", "application/json");
        request.response().write(buffer);
        request.response().setStatusCode(HttpStatus.SC_OK);
        request.response().end();
    }

    /**
     * Writes the PUT response for the target server.
     *
     * @param request request to the target server
     * @param expectedDelta the delta we actually expect
     * @param checkMap the map indicating if the delta was written
     */
    private void writePUTDeltaResponse(final HttpServerRequest request, final int expectedDelta, Map<String, Integer> checkMap) {
        request.bodyHandler(body -> {
            JsonObject content = new JsonObject(body.toString());
            if (content.getInteger("x-delta") != expectedDelta) {
                log.debug("DeltaSync is: " + content.getInteger(("x-delta")) + " but expected is: " + expectedDelta );
                request.response().setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR);
                request.response().end();
            } else {
                log.debug("DeltaSync is fine" );
                checkMap.put("x-delta", content.getInteger("x-delta"));
                request.response().setStatusCode(HttpStatus.SC_OK);
                request.response().end();
            }
        });
    }

    @Test
    public void testMirror_replaceInvalidDeltaParameter(TestContext context) {
        ResourcesMirrorHandler rmh = new ResourcesMirrorHandler(null, null, null, null, null);

        /*
         * case 1
         *      start:  /mirror/test_mirror.zip?delta=1000
         *      result: /mirror/test_mirror.zip?delta=20
         *
         */
        String start = "/mirror/test_mirror.zip?delta=1000";
        String result = "/mirror/test_mirror.zip?delta=20";
        context.assertEquals(result, rmh.replaceInvalidDeltaParameter(start, 20));


        /*
         * case 2
         *      start:  /mirror/test_mirror.zip?delta=1000&lola=rennt
         *      result: /mirror/test_mirror.zip?delta=20&lola=rennt
         *
         */
        start = "/mirror/test_mirror.zip?delta=1000&lola=rennt";
        result = "/mirror/test_mirror.zip?delta=20&lola=rennt";
        context.assertEquals(result, rmh.replaceInvalidDeltaParameter(start, 20));


        /*
         * case 3
         *      start:  /mirror/test_mirror.zip?lola=rennt&delta=1000
         *      result: /mirror/test_mirror.zip?lola=rennt&delta=20
         *
         */
        start = "/mirror/test_mirror.zip?lola=rennt&delta=1000";
        result = "/mirror/test_mirror.zip?lola=rennt&delta=20";
        context.assertEquals(result, rmh.replaceInvalidDeltaParameter(start, 20));


        /*
         * case 4
         *      start:  /mirror/test_mirror.zip?lola=rennt&delta=1000&mega=true
         *      result: /mirror/test_mirror.zip?lola=rennt&delta=20&mega=true
         *
         */
        start = "/mirror/test_mirror.zip?lola=rennt&delta=1000&mega=true";
        result = "/mirror/test_mirror.zip?lola=rennt&delta=20&mega=true";
        context.assertEquals(result, rmh.replaceInvalidDeltaParameter(start, 20));

        /*
         * case 5
         *      start:  /mirror/test_mirror.zip?lola=rennt&delta=1000&mega=true
         *      result: /mirror/test_mirror.zip?lola=rennt&delta=20&mega=true
         *
         */
        start = "/mirror/test_mirror.zip?lola=rennt&mega=true";
        result = "/mirror/test_mirror.zip?lola=rennt&mega=true";
        context.assertEquals(result, rmh.replaceInvalidDeltaParameter(start, 20));
    }

    @Test
    public void testMirror_GetZipDeployZip(TestContext context) {
        Async async = context.async();

        /*
         * Settings
         */
        final String path = "test_mirror.zip";


        // emulate a target server
        targetServer = createServer(TARGET_PORT, request -> {

            /*
             * Add all responses the target server has
             * to provide.
             */

            // add put and its success to the map (overwrite false)
            String key = request.uri();
            key = key.substring(1);
            entries.put(key, true);
            request.response().end();
            return null;
        });


        // emulate a source server
        sourceServer = createServer(SOURCE_PORT, request -> {
            if ( request.uri().endsWith(path) ) {
                writeZipStream(request, path);
            }
            else {
                request.response().setStatusCode(HttpStatus.SC_NOT_FOUND);
            }

            request.response().end();

            return null;
        });

        // Function which performs all necessary tests
        Function<HttpClientResponse, Void> testFunction = httpClientResponse -> {
            log.debug("Result is: " );
            context.assertEquals(HttpStatus.SC_OK, httpClientResponse.statusCode());
            httpClientResponse.bodyHandler( body -> {
                JsonObject result = new JsonObject(body.toString());

                // all elements ok?
                context.assertTrue(result.getBoolean("success"));
                JsonArray results = result.getJsonArray("loadedresources");

                // correct count?
                context.assertEquals(entries.size(), results.size());

                for (Object object : results) {
                    if ( object instanceof JsonObject ) {
                        JsonObject entry = (JsonObject) object;
                        log.debug("" + entry);

                        // every single entry correct and contained in set?
                        context.assertTrue(entries.containsKey(entry.getString("path")));
                        context.assertTrue(entries.get(entry.getString("path")));
                        context.assertTrue(entry.getBoolean("success"));
                    }
                    else {
                        context.fail();
                    }
                }

                async.complete();
            });

            return null;
        };

        // start the tests ...
        performMirrorRequest(path, testFunction, null);
    }

    @Test
    public void testMirror_notFound(TestContext context) {
        Async async = context.async();

        final String path = "not_found.zip";


        // emulate a target server
        targetServer = createServer(TARGET_PORT, request -> {
            request.response().end();
            return null;
        });

        // emulate a source server
        sourceServer = createServer(SOURCE_PORT, request -> {
            request.response().setStatusCode(HttpStatus.SC_NOT_FOUND);
            request.response().end();
            return null;
        });

        // Function which performs all necessary tests
        Function<HttpClientResponse, Void> testFunction = httpClientResponse -> {
            context.assertEquals(HttpStatus.SC_NOT_FOUND, httpClientResponse.statusCode());
            async.complete();
            return null;
        };

        // start the tests ...
        performMirrorRequest(path, testFunction, null);

    }


    @Test
    public void testMirror_delta_init(TestContext context) {
        Async async = context.async();

        // Settings
        final String path = "test_mirror.zip";
        final String deltaPath = "test_mirror/value";
        final int startingDelta = 0;
        final int expectedDelta = 100;

        // Working variables
        final AtomicInteger elementCount = new AtomicInteger(0);
        final Map<String, Integer> checked = new HashMap<>();

        // emulate a target server
        targetServer = createServer(TARGET_PORT, request -> {
            log.debug("Request: " + request.uri() + ", Method: " + request.method() );
            // deltasync request
            if ( request.uri().endsWith(deltaPath) ) {
                log.debug("DeltaPath request: " + deltaPath);
                // GET to check the current value
                if ( request.method() == HttpMethod.GET ) {
                    log.debug(" > using GET method" );
                    writeGETDeltaResponse(request,startingDelta);
                }
                // PUT to set the new value
                else if ( request.method() == HttpMethod.PUT ) {
                    log.debug(" > using PUT method" );
                    writePUTDeltaResponse(request, expectedDelta, checked);
                }
                // wrong ...
                else {
                    log.debug(" > using wrong method" );
                    request.response().setStatusCode(HttpStatus.SC_BAD_REQUEST);
                    request.response().end();
                }
            }
            // put of elements
            else if ( entries.containsKey(request.uri().substring(1)) ) {
                elementCount.incrementAndGet();
                request.response().setStatusCode(HttpStatus.SC_OK);
                request.response().end();
            }
            // something wrong
            else {
                request.response().setStatusCode(HttpStatus.SC_BAD_REQUEST);
                request.response().end();
            }

            return null;
        });


        // emulate a source server
        sourceServer = createServer(SOURCE_PORT, request -> {
            if ( request.uri().endsWith(path + "?delta=" + startingDelta) ) {
                // write the new delta header
                request.response().headers().add("x-delta", String.valueOf(expectedDelta));

                // write the stream
                writeZipStream(request, path);
            }
            else {
                request.response().setStatusCode(HttpStatus.SC_NOT_FOUND);
            }

            // end response
            request.response().end();

            return null;
        });



        // Function which performs all necessary tests
        Function<HttpClientResponse, Void> testFunction = httpClientResponse -> {
            context.assertEquals(HttpStatus.SC_OK, httpClientResponse.statusCode());
            httpClientResponse.bodyHandler( body -> {
                JsonObject result = new JsonObject(body.toString());
                JsonArray results = result.getJsonArray("loadedresources");

                // correct count?
                context.assertEquals(entries.size(), results.size());
                context.assertEquals(entries.size(), elementCount.get());

                // deltasync
                context.assertTrue(checked.containsKey("x-delta"));
                context.assertEquals(checked.get("x-delta"), expectedDelta);

                async.complete();
            });

            return null;
        };

        // start the tests ...
        performMirrorRequest(path, testFunction, deltaPath);

    }

    @Test
    public void testMirror_delta_to_high(TestContext context) {
        Async async = context.async();

        // Settings
        final String path = "test_mirror.zip";
        final String wrongZip = "mirror-test.zip";
        final String deltaPath = "test_mirror/value";
        final int startingDelta = 1000;
        final int expectedStartingDelta = 0;
        final int expectedDelta = 100;

        // Working variables
        final Map<String, Integer> checked = new HashMap<>();
        final AtomicInteger sourceRequestCount = new AtomicInteger(0);
        final AtomicInteger wrongDeltaRequestCount = new AtomicInteger(0);
        final AtomicInteger correctDeltaRequestCount = new AtomicInteger(0);


        // emulate a target server
        targetServer = createServer(TARGET_PORT, request -> {
            log.debug("Request: " + request.uri() + ", Method: " + request.method() );
            // deltasync request
            if ( request.uri().endsWith(deltaPath) ) {
                log.debug("DeltaPath request: " + deltaPath);
                // GET to check the current value
                if ( request.method() == HttpMethod.GET ) {
                    log.debug(" > using GET method" );
                    writeGETDeltaResponse(request,startingDelta);
                }
                // PUT to set the new value
                else if ( request.method() == HttpMethod.PUT ) {
                    log.debug(" > using PUT method" );
                    writePUTDeltaResponse(request, expectedDelta, checked);
                }
                // wrong ...
                else {
                    log.debug(" > using wrong method" );
                    request.response().setStatusCode(HttpStatus.SC_BAD_REQUEST);
                    request.response().end();
                }
            }
            // put of elements
            else if ( entries.containsKey(request.uri().substring(1)) || request.uri().startsWith("/server/tests/") ) {
                request.response().setStatusCode(HttpStatus.SC_OK);
                request.response().end();
            }
            // something wrong
            else {
                log.debug("unknown request for " + request.uri() + " with method " + request.method());
                request.response().setStatusCode(HttpStatus.SC_BAD_REQUEST);
                request.response().end();
            }

            return null;
        });

        // emulate a source server
        sourceServer = createServer(SOURCE_PORT, request -> {
            if ( request.uri().endsWith(path + "?delta=" + startingDelta) ) {
                log.debug("Request is performed with: " + request.uri() );

                sourceRequestCount.incrementAndGet();
                wrongDeltaRequestCount.incrementAndGet();

                // write the real delta header
                request.response().headers().add("x-delta", String.valueOf(expectedDelta));

                // write the stream
                 writeZipStream(request, wrongZip);
            }
            else if ( request.uri().endsWith(path + "?delta=" + expectedStartingDelta) ) {
                log.debug("Request is performed with: " + request.uri() );

                sourceRequestCount.incrementAndGet();
                correctDeltaRequestCount.incrementAndGet();

                // write the real delta header
                request.response().headers().add("x-delta", String.valueOf(expectedDelta));

                // write the stream
                writeZipStream(request, path);
            }
            else {
                request.response().setStatusCode(HttpStatus.SC_NOT_FOUND);
            }

            // end response
            request.response().end();

            return null;
        });

        // Function which performs all necessary tests
        Function<HttpClientResponse, Void> testFunction = httpClientResponse -> {
            context.assertEquals(HttpStatus.SC_OK, httpClientResponse.statusCode());
            httpClientResponse.bodyHandler( body -> {
                JsonObject result = new JsonObject(body.toString());
                JsonArray results = result.getJsonArray("loadedresources");

                // correct count?
                context.assertEquals(entries.size(), results.size());

                // deltasync
                context.assertTrue(checked.containsKey("x-delta"));
                context.assertEquals(checked.get("x-delta"), expectedDelta);

                // request count
                context.assertEquals(2, sourceRequestCount.get());
                context.assertEquals(1, wrongDeltaRequestCount.get());
                context.assertEquals(1, correctDeltaRequestCount.get());

                async.complete();
            });

            return null;
        };

        // start the tests ...
        performMirrorRequest(path, testFunction, deltaPath);

    }

    @Test
    public void testMirror_delta_corrupt(TestContext context) {
        Async async = context.async();

        // Settings
        final String path = "test_mirror.zip";

        final String deltaPath = "test_mirror/value";
        final int startingDelta = 50;
        final int newDelta = 100;
        final int expectedDelta = 50;
        final Set<String> requestsToFail = new HashSet<>();
        requestsToFail.add("/test_mirror/t2/t22/t31/test.png");
        requestsToFail.add("/test_mirror/t2/t21/test.html");

        // Working variables
        final Map<String, Integer> checked = new HashMap<>();
        final AtomicInteger successfulRequestCount = new AtomicInteger(0);
        final AtomicInteger failedRequestCount = new AtomicInteger(0);
        final AtomicInteger totalRequstCount = new AtomicInteger(0);
        checked.put("x-delta", startingDelta);

        // emulate a target server
        targetServer = createServer(TARGET_PORT, request -> {
            log.debug("Request: " + request.uri() + ", Method: " + request.method() );
            // deltasync request
            if ( request.uri().endsWith(deltaPath) ) {
                log.debug("DeltaPath request: " + deltaPath);
                // GET to check the current value
                if ( request.method() == HttpMethod.GET ) {
                    log.debug(" > using GET method" );
                    writeGETDeltaResponse(request,startingDelta);
                }
                // PUT to set the new value
                else if ( request.method() == HttpMethod.PUT ) {
                    log.debug(" > using PUT method" );
                    writePUTDeltaResponse(request, newDelta, checked);
                }
                // wrong ...
                else {
                    log.debug(" > using wrong method" );
                    request.response().setStatusCode(HttpStatus.SC_BAD_REQUEST);
                    request.response().end();
                }
            }
            // put of elements
            else if ( entries.containsKey(request.uri().substring(1)) ) {
                totalRequstCount.incrementAndGet();

                String entry = request.uri();

                // two request will / have to fail
                if ( requestsToFail.contains(entry) ) {
                    log.debug("create a fake fail request for: " + request.uri() );
                    failedRequestCount.incrementAndGet();
                    request.response().setStatusCode(HttpStatus.SC_GATEWAY_TIMEOUT);
                }
                // all others are ok
                else {
                    log.debug("request is ok: " + request.uri());
                    successfulRequestCount.incrementAndGet();
                    request.response().setStatusCode(HttpStatus.SC_OK);
                }

                request.response().end();
            }
            // something wrong
            else {
                request.response().setStatusCode(HttpStatus.SC_BAD_REQUEST);
                request.response().end();
            }

            return null;
        });

        // emulate a source server
        sourceServer = createServer(SOURCE_PORT, request -> {
            if ( request.uri().endsWith(path + "?delta=" + startingDelta) ) {
                // write the real delta header
                request.response().headers().add("x-delta", String.valueOf(newDelta));

                // write the stream
                writeZipStream(request, path);
            }
            else {
                request.response().setStatusCode(HttpStatus.SC_NOT_FOUND);
            }

            // end response
            request.response().end();

            return null;
        });

        // Function which performs all necessary tests
        Function<HttpClientResponse, Void> testFunction = httpClientResponse -> {
            context.assertEquals(HttpStatus.SC_OK, httpClientResponse.statusCode());
            httpClientResponse.bodyHandler( body -> {
                JsonObject result = new JsonObject(body.toString());
                JsonArray results = result.getJsonArray("loadedresources");

                // correct count?
                context.assertEquals(entries.size(), results.size());

                // deltasync
                context.assertTrue(checked.containsKey("x-delta"));
                context.assertEquals(checked.get("x-delta"), expectedDelta);

                // request count
                context.assertEquals(3, successfulRequestCount.get());
                context.assertEquals(2, failedRequestCount.get());
                context.assertEquals(entries.size(), totalRequstCount.get());

                // content check
                int failedCounter = 0;
                int successCounter = 0;
                for (Object object : results) {
                    if ( object instanceof JsonObject ) {
                        JsonObject entry = (JsonObject) object;
                        log.debug("" + entry);

                        // should be in list
                        context.assertTrue(entries.containsKey(entry.getString("path")));

                        if ( requestsToFail.contains("/" +entry.getString("path")) ){
                           failedCounter++;
                           context.assertFalse(entry.getBoolean("success"));
                        }
                        else {
                            successCounter++;
                            context.assertTrue(entry.getBoolean("success"));
                        }
                    }
                    else {
                        context.fail();
                    }
                }

                context.assertEquals(3, successCounter);
                context.assertEquals(2, failedCounter);

                log.debug("Content:");
                log.debug(Json.encodePrettily(result));
                async.complete();
            });

            return null;
        };

        // start the tests ...
        performMirrorRequest(path, testFunction, deltaPath);

    }

    @AfterClass
    public static void tearDown(TestContext context) {
        Async async = context.async();
        vertx.close(event -> async.complete());
    }
}
