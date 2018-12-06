package org.swisspush.mirror;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@RunWith(VertxUnitRunner.class)
public class ZipContentHandlerTest {

    @Test
    public void testReadZipEntries(TestContext context) throws IOException {

        InputStream is = this.getClass().getResourceAsStream("/mirror-test.zip");

        ZipIterator zipFileEntryIterator = new ZipIterator(is);

        Map<String, Buffer> zipEntries = new HashMap<>();
        while (zipFileEntryIterator.hasNext()) {
            UnzippedResource unzippedResource = zipFileEntryIterator.next();
            zipEntries.put(unzippedResource.filename, unzippedResource.buffer);
        }

        context.assertEquals(5, zipEntries.size());

        context.assertEquals(  977, zipEntries.get("server/tests/mirror/load/browser.css"                        ).length());
        context.assertEquals( 2608, zipEntries.get("server/tests/mirror/load/loader.gif"                         ).length());
        context.assertEquals(  204, zipEntries.get("server/tests/mirror/load/test.html"                          ).length());
        context.assertEquals(34066, zipEntries.get("server/tests/mirror/load/test.png"                           ).length());
        context.assertEquals(   22, zipEntries.get("server/tests/mirror/load/mirror1/mirror21/mirror30/test.json").length());
    }

}