package org.swisspush.mirror;


import java.io.IOException;
import java.io.InputStream;
import java.util.NoSuchElementException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Iterates over the content of a ZIP.
 * <ul>
 *     <li>Behaves like a {@link java.util.Iterator} - but does not formally implement it as we don't want to hide Exceptions</li>
 *     <li>ZIP must be provided as InputStream</li>
 *     <li>Only <b>file</b>-entries are are used (in other words: directory-entries in the ZIP are skipped/ignored)</li>
 * </ul>
 *
 * @author Oliver Henning
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
     * @return the next UnzippedResource (containing relative path as in ZIP-Entry and unZIPped content as a Vert.x Buffer). See {@link java.util.Iterator#next()}
     *
     * @throws IOException from the underlying ZipInputStream
     * @throws ZipException from the underlying ZipInputStream
     * @throws NoSuchElementException see {@link java.util.Iterator#next()}
     */
    public UnzippedResource next() throws IOException, NoSuchElementException {
        if (unzippedResource == null && !hasNext()) {
            throw new NoSuchElementException("done");
        }
        UnzippedResource current = unzippedResource;
        unzippedResource = null;
        return current;
    }

    /**
     * @return true when {@link #next()} would return a non-null value. See {@link java.util.Iterator#hasNext()}
     *
     * @throws IOException from the underlying ZipInputStream
     * @throws ZipException from the underlying ZipInputStream
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
