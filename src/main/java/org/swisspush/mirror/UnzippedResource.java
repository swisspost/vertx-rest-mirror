package org.swisspush.mirror;

import io.vertx.core.buffer.Buffer;

/**
 * A holder (tuple) for an unZIPped entry:
 * <ul>
 *     <li>filename (as in ZIP, i.e. relative path)<br>plus</li>
 *     <li>file-content as Vert.x Buffer</li>
 * </ul>
 *
 * @author Oliver Henning
 */
public class UnzippedResource {
    public final String filename;
    public final Buffer buffer = Buffer.buffer();

    public UnzippedResource(String filename) {
        this.filename = filename;
    }
}
