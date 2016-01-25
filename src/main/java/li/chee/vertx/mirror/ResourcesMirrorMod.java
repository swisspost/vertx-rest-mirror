package li.chee.vertx.mirror;

import org.vertx.java.core.http.HttpClient;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.platform.Verticle;

/**
 * Initializes the ResourcesMirrorHandler.
 *
 * @author: Florian Kammermann
 */
public class ResourcesMirrorMod extends Verticle {

    @Override
    public void start() {

        JsonObject config = container.config();
        Logger log = container.logger();

        // the port where the http server is listen on
        int serverPort = config.getNumber("serverPort", 8686).intValue();

        // the host and port where the ResourceMirrorHandler will put the resources from within the zip file
        String selfHost = config.getString("selfClientHost", "localhost");
        int selfPort = config.getNumber("selfClientPort", 7012).intValue();
        int selfTimeout = config.getNumber("selfClientTimeout", 30000).intValue();

        // the host and port where the ResourceMirrorHandler will access the zip file
        String mirrorHost = config.getString("mirrorHost", "localhost");
        int mirrorPort = config.getNumber("mirrorPort", 7012).intValue();
        int mirrorTimeout = config.getNumber("selfClientTimeout", 30000).intValue();

        // the root path used for accessing the zip file
        String mirrorRootPath = config.getString("mirrorRootPath", "/root");

        final HttpClient selfClient = vertx.createHttpClient().setHost(selfHost).setPort(selfPort).setMaxPoolSize(25).setKeepAlive(true).setPipelining(false).setConnectTimeout(selfTimeout);
        final HttpClient mirrorClient = vertx.createHttpClient().setHost(mirrorHost).setPort(mirrorPort).setMaxPoolSize(25).setKeepAlive(true).setPipelining(false).setConnectTimeout(mirrorTimeout);

        vertx.createHttpServer().requestHandler(new ResourcesMirrorHandler(log, mirrorRootPath, mirrorClient, selfClient)).listen(serverPort);
    }
}
