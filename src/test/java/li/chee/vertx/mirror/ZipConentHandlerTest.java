package li.chee.vertx.mirror;

import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@RunWith(VertxUnitRunner.class)
public class ZipConentHandlerTest {

    @Test
    public void testReadZipEntries(TestContext context) throws Exception {

        InputStream in = this.getClass().getClassLoader().getResourceAsStream("mirror-test.zip");

        ZipFileEntryIterator zipFileEntryIterator = new ZipFileEntryIterator(in, null);

        Map<String, byte[]> zipEntries = new HashMap<>();
        while(zipFileEntryIterator.hasNext()) {
            Map<String, byte[]> zipFileEntry = zipFileEntryIterator.next();
            zipEntries.putAll(zipFileEntry);
        }

        context.assertEquals(5, zipEntries.size());

        context.assertEquals(977, zipEntries.get("server/tests/mirror/load/browser.css").length);
        context.assertEquals(2608, zipEntries.get("server/tests/mirror/load/loader.gif").length);
        context.assertEquals(204, zipEntries.get("server/tests/mirror/load/test.html").length);
        context.assertEquals(34066, zipEntries.get("server/tests/mirror/load/test.png").length);
        context.assertEquals(22, zipEntries.get("server/tests/mirror/load/mirror1/mirror21/mirror30/test.json").length);
    }

}