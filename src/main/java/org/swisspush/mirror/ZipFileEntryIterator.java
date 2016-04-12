package org.swisspush.mirror;

import io.vertx.core.logging.Logger;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Iterates over the content of a zip.
 * The zip can be provided as InputStream or as URL.
 * Only files are considered as valid zip entries.
 *
 * @author: Florian Kammermann
 */
public class ZipFileEntryIterator implements Iterator<Map<String, byte[]>> {

    private Logger log;
    private ZipInputStream zis;
    private List<Map<String, byte[]>> zipEntries = new ArrayList<>();
    private ZipEntry nextZipEntry;

    public ZipFileEntryIterator(URL zipUrl, Logger log) throws IOException {
        this.log = log;
        InputStream inputStream = zipUrl.openStream();
        if(inputStream == null) {
            throw new IOException("could not open stream on url: " + zipUrl);
        }
        this.zis = new ZipInputStream(new BufferedInputStream(inputStream));
    }

    public ZipFileEntryIterator(InputStream inputStream, Logger log) {
        this.log = log;
        zis = new ZipInputStream(new BufferedInputStream(inputStream));
    }

    @Override
    public Map<String, byte[]> next() {
        if(this.nextZipEntry == null) {
            return null;
        }
        hasNext();
        if(this.nextZipEntry == null) {
            return null;
        }
        Map<String, byte[]> extractedZipEntry = extractZipEntry(this.nextZipEntry);
        this.nextZipEntry = null;
        return extractedZipEntry;
    }

    @Override
    public boolean hasNext() {
        if(this.nextZipEntry != null) {
            return true;
        }
        if(this.zis == null) {
            return false;
        }
        try {
            boolean isDirectory = true;
            while(isDirectory) {
                nextZipEntry = zis.getNextEntry();
                if(nextZipEntry == null) {
                    break;
                }
                String filename = nextZipEntry.getName();
                if (!filename.matches(".*/$")) {
                    isDirectory = false;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if(this.nextZipEntry != null) {
            return true;
        }
        try {
            zis.close();
            zis = null;
        } catch (IOException e) {
            log.error("exception while extracting files from zip: " + e.getMessage());
        }
        return false;
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }

    private Map<String, byte[]> extractZipEntry(ZipEntry zipEntry) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            try {
                int data = 0;
                while ((data = zis.read()) != -1) {
                    output.write(data);
                }
                output.close();
            } catch (IOException e) {
                log.error("exception while extracting files from zip: " + e.getMessage());
            }

            String filename = nextZipEntry.getName();
            Map<String, byte[]> extractedZipEntry = new HashMap<>();
            extractedZipEntry.put(filename, output.toByteArray());
            return extractedZipEntry;

    }

}
