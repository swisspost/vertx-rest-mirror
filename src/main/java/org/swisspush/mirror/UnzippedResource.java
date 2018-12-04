package org.swisspush.mirror;

import io.vertx.core.buffer.Buffer;

/**
 * A holder (tuple) for an unzipped entry: filename (as in ZIP, i.e. relative path) plus file-content as Vert.x Buffer
 */
public class UnzippedResource {
    public final String filename;
    public final Buffer buffer = Buffer.buffer();

    public UnzippedResource(String filename) {
        this.filename = filename;
    }
}
