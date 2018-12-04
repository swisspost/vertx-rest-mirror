package org.swisspush.mirror;


import java.io.IOException;
import java.io.InputStream;
import java.util.NoSuchElementException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Iterates over the content of a zip.
 * The zip must be provided as InputStream
 * Only files within the ZIP are considered as valid zip entries (i.e. directory entries in ZIP are skipped/ignored).
 *
 * @author: Oliver Henning
 */
public class ZipIterator {

    private ZipInputStream zis;
    private UnzippedResource unzippedResource;

    // assume single threaded access
    private byte[] buf = new byte[1024];

    public ZipIterator(InputStream is) {
        zis = new ZipInputStream(is);
    }

    /**
     * @return the next UnzippedResource (containing relative path as in ZIP-Entry and unZIPped content as a Vert.x Buffer)
     *
     * @throws IOException
     * @throws ZipException
     * @throws NoSuchElementException
     */
    public UnzippedResource next() throws IOException, NoSuchElementException {
        if (unzippedResource == null) {
            if (!hasNext()) {
                throw new NoSuchElementException("done");
            }
        }
        UnzippedResource current = unzippedResource;
        unzippedResource = null;
        return current;
    }

    /**
     * @return true when {@link #next() would return a non-null value}
     * @throws IOException
     * @throws ZipException
     */
    public boolean hasNext() throws IOException {
        if (zis == null) {
            return false;
        }
        while (unzippedResource == null) {
            ZipEntry ze = zis.getNextEntry();
            if (ze == null) {
                try {
                    zis.close();
                } catch (IOException ex) {
                    // silently close
                }
                zis = null;
                return false;
            }
            String filename = ze.getName();
            if (!filename.endsWith("/")) {
                unzippedResource = new UnzippedResource(filename);
                int count;
                while ((count = zis.read(buf)) >= 0) {
                    unzippedResource.buffer.appendBytes(buf, 0, count);
                }
            }
        }
        return true;
    }
}
