package org.swisspush.mirror;

import io.vertx.core.buffer.Buffer;

import java.io.InputStream;

/**
 * Wraps a Vert.x Buffer to a classic Java-IO InputStream.
 * We need this to handle Buffer content as a ZipInputStream
 * <br>
 * Note that this avoids in-memory data duplication as happened when we used "new ByteArrayInputStream(buffer.getBytes())"
 *
 * @author Oliver Henning
 */
public class BufferWrapperInputStream extends InputStream {

    private final Buffer buffer;
    private int pos = 0;

    public BufferWrapperInputStream(Buffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public int read() {
        if (pos >= buffer.length()) {
            return -1;
        }
        return buffer.getByte(pos++) & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) {
        // few lines copied from java.io.InputStream to ensure same behaviour
        if (b == null) {
            throw new NullPointerException();
        } else if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return 0;
        }

        int count = Integer.min(len, buffer.length() - pos);
        if (count <= 0) {
            return -1;
        }
        buffer.getBytes(pos, pos + count, b, off);
        pos += count;
        return count;
    }
}
