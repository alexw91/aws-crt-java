package software.amazon.awssdk.crt.utils;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A PipedInputStream buffers a connection between a Writer Thread (the Native EventLoop), to a Reader Thread
 * (Client User Thread) so that each end can act independent of the other.
 *
 * We extend Java's PipedInputStream to provide event callbacks so that we can notify the Native EventLoop
 * (by updating Window sizes) how much data has been read from the Stream, so that the Native EventLoop will write data
 * only when there is space available.
 *
 */
public class AwsCrtPipedInputStream extends PipedInputStream {
    private final static int DEFAULT_BUFFER_SIZE = 16 * 1024;
    private final AwsCrtInputStreamEvent callback;
    private boolean aborted = false;
    private String abortedMsg = null;

    public AwsCrtPipedInputStream(PipedOutputStream out, AwsCrtInputStreamEvent callback) throws IOException {
        this(out, callback, DEFAULT_BUFFER_SIZE);
    }

    public AwsCrtPipedInputStream(PipedOutputStream out, AwsCrtInputStreamEvent callback, int bufferSize) throws IOException {
        super(out, bufferSize);
        this.callback = callback;
    }

    @Override
    public synchronized int read() throws IOException {
        int value = super.read();
        if (callback != null) {
            callback.notifyWriteSpaceAvailable(writeSpaceAvailable());
        }
        return value;
    }

    @Override
    public synchronized int read(byte b[], int off, int len)  throws IOException {
        int amountRead = super.read(b, off, len);
        if (callback != null) {
            callback.notifyWriteSpaceAvailable(writeSpaceAvailable());
        }
        return amountRead;
    }


    /**
     * Returns the number of bytes that can be read without blocking.
     * @return
     * @throws IOException
     */
    public synchronized int readSpaceAvailable() throws IOException {
        return super.available();
    }

    /**
     * Returns the number of bytes that can be written without blocking.
     * @return
     * @throws IOException
     */
    public synchronized int writeSpaceAvailable() throws IOException {
        return buffer.length - super.available();
    }

    public synchronized void abort(String msg) {
        this.aborted = true;
        this.abortedMsg = msg;
    }

    @Override
    public void close() throws IOException {
        super.close();
        callback.notifyStreamClosed();
    }
}
