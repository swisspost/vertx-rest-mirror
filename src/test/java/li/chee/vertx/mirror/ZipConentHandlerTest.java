package li.chee.vertx.mirror;

import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.logging.Logger;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.hamcrest.CoreMatchers.*;

public class ZipConentHandlerTest {

    @Test
    public void testReadZipEntries() throws Exception {

        InputStream in = this.getClass().getClassLoader().getResourceAsStream("mirror-test.zip");

        ZipFileEntryIterator zipFileEntryIterator = new ZipFileEntryIterator(in, null);

        Map<String, byte[]> zipEntries = new HashMap<>();
        while(zipFileEntryIterator.hasNext()) {
            Map<String, byte[]> zipFileEntry = zipFileEntryIterator.next();
            zipEntries.putAll(zipFileEntry);
        }

        assertThat(zipEntries.size(), is(5));

        assertThat(zipEntries.get("server/tests/mirror/load/browser.css").length, is(977));
        assertThat(zipEntries.get("server/tests/mirror/load/loader.gif").length, is(2608));
        assertThat(zipEntries.get("server/tests/mirror/load/nemo.html").length, is(204));
        assertThat(zipEntries.get("server/tests/mirror/load/nemo.png").length, is(34066));
        assertThat(zipEntries.get("server/tests/mirror/load/mirror1/mirror21/mirror30/test.json").length, is(22));

    }

}