// SPDX-License-Identifier: BSD-3-Clause AND (GPL-3.0-or-later OR Apache-2.0)

package io.github.muntashirakon.adb;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class abstracts the underlying ADB streams
 */
// Copyright 2013 Cameron Gutman
public class AdbStream implements Closeable {

    /**
     * The AdbConnection object that the stream communicates over
     */
    private final AdbConnection mAdbConnection;

    /**
     * The local ID of the stream
     */
    private final int mLocalId;

    /**
     * The remote ID of the stream
     */
    private volatile int mRemoteId;

    /**
     * Indicates whether WRTE is currently allowed
     */
    private final AtomicBoolean mWriteReady;
    /** Keep concurrent callers' payloads ordered while ACK processing uses the stream monitor. */
    private final Object mWriteLock = new Object();

    /**
     * A queue of data from the target's WRTE packets
     */
    private final Queue<byte[]> mReadQueue;

    /**
     * Store data received from the first WRTE packet in order to support buffering.
     */
    private final ByteBuffer mReadBuffer;

    /**
     * Indicates whether the connection is closed already
     */
    private volatile boolean mIsClosed;

    /**
     * Whether the remote peer has closed but we still have unread data in the queue
     */
    private volatile boolean mPendingClose;

    /**
     * Creates a new AdbStream object on the specified AdbConnection
     * with the given local ID.
     *
     * @param adbConnection AdbConnection that this stream is running on
     * @param localId       Local ID of the stream
     */
    AdbStream(AdbConnection adbConnection, int localId)
            throws IOException, InterruptedException, AdbPairingRequiredException {
        this.mAdbConnection = adbConnection;
        this.mLocalId = localId;
        this.mReadQueue = new ConcurrentLinkedQueue<>();
        this.mReadBuffer = (ByteBuffer) ByteBuffer.allocate(adbConnection.getMaxData()).flip();
        this.mWriteReady = new AtomicBoolean(false);
        this.mIsClosed = false;
    }

    public AdbInputStream openInputStream() {
        return new AdbInputStream(this);
    }

    public AdbOutputStream openOutputStream() {
        return new AdbOutputStream(this);
    }

    /**
     * Called by the connection thread to indicate newly received data.
     *
     * @param payload Data inside the WRTE message
     */
    boolean isReady() { return mRemoteId != 0; }

    void addPayload(byte[] payload) throws IOException {
        synchronized (mReadQueue) {
            if (mReadQueue.size() >= 16) throw new IOException("ADB output queue exceeded");
            mReadQueue.add(payload);
            mReadQueue.notifyAll();
        }
    }

    /**
     * Called by the connection thread to send an OKAY packet, allowing the
     * other side to continue transmission.
     *
     * @throws IOException If the connection fails while sending the packet
     */
    void sendReady() throws IOException {
        // Generate and send a OKAY packet
        mAdbConnection.sendPacket(AdbProtocol.generateReady(mLocalId, mRemoteId));
    }

    /**
     * Called by the connection thread to update the remote ID for this stream
     *
     * @param remoteId New remote ID
     */
    void updateRemoteId(int remoteId) {
        this.mRemoteId = remoteId;
    }

    /**
     * Called by the connection thread to indicate the stream is okay to send data.
     */
    void readyForWrite() {
        mWriteReady.set(true);
    }

    /**
     * Called by the connection thread to notify that the stream was closed by the peer.
     */
    void notifyClose(boolean closedByPeer) {
        synchronized (mReadQueue) {
            mPendingClose = closedByPeer;
            if (!closedByPeer || (mReadQueue.isEmpty() && !mReadBuffer.hasRemaining())) mIsClosed = true;
            mReadQueue.notifyAll();
        }
        synchronized (this) { notifyAll(); }
    }

    /** Drain buffered output before reporting EOF, even when CLSE races the reader. */
    public int read(byte[] bytes, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (mReadBuffer.hasRemaining()) return readBuffer(bytes, offset, length);
        synchronized (mReadQueue) {
            byte[] data;
            while ((data = mReadQueue.poll()) == null) {
                if (mIsClosed || mPendingClose) { mIsClosed = true; return -1; }
                try { mReadQueue.wait(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Read cancelled", e); }
            }
            mReadBuffer.clear(); mReadBuffer.put(data); mReadBuffer.flip();
            return readBuffer(bytes, offset, length);
        }
    }

    private int readBuffer(byte[] bytes, int offset, int length) {
        int count = 0;
        for (int i = offset; i < offset + length; ++i) {
            if (mReadBuffer.hasRemaining()) {
                bytes[i] = mReadBuffer.get();
                ++count;
            }
        }
        return count;
    }

    /**
     * Sends a WRTE packet with a given byte array payload. It does not flush the stream.
     *
     * @param bytes Payload in the form of a byte array
     * @throws IOException If the stream fails while sending data
     */
    public void write(byte[] bytes, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        if (length == 0) return; // An empty write must not consume the next OKAY.
        synchronized (mWriteLock) {
            final int maxData;
            try {
                maxData = mAdbConnection.getMaxData();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Write cancelled", e);
            } catch (AdbPairingRequiredException e) {
                throw new IOException("ADB pairing required", e);
            }
            if (maxData <= 0) throw new IOException("Invalid negotiated ADB payload size");
            while (length > 0) {
                synchronized (this) {
                    while (!mIsClosed && !mPendingClose && !mWriteReady.compareAndSet(true, false)) {
                        try { wait(); }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Write cancelled", e);
                        }
                    }
                    if (mIsClosed || mPendingClose) throw new IOException("Stream closed");
                }
                int count = Math.min(length, maxData);
                mAdbConnection.sendPacket(AdbProtocol.generateWrite(mLocalId, mRemoteId, bytes, offset, count));
                offset += count;
                length -= count;
            }
        }
    }

    public void flush() throws IOException {
        if (mIsClosed) {
            throw new IOException("Stream closed");
        }
        mAdbConnection.flushPacket();
    }

    /**
     * Closes the stream. This sends a close message to the peer.
     *
     * @throws IOException If the stream fails while sending the close message.
     */
    @Override
    public void close() throws IOException {
        synchronized (this) {
            // This may already be closed by the remote host
            if (mIsClosed)
                return;

            // Notify readers/writers that we've closed
            notifyClose(false);
        }

        mAdbConnection.sendPacket(AdbProtocol.generateClose(mLocalId, mRemoteId));
    }

    /**
     * Returns whether the stream is closed or not
     *
     * @return True if the stream is close, false if not
     */
    public boolean isClosed() {
        return mIsClosed;
    }

    /**
     * Returns an estimate of available data.
     *
     * @return an estimate of the number of bytes that can be read from this stream without blocking.
     * @throws IOException if the stream is close.
     */
    public int available() throws IOException {
        synchronized (this) {
            if (mIsClosed) {
                throw new IOException("Stream closed.");
            }
            if (mReadBuffer.hasRemaining()) {
                return mReadBuffer.remaining();
            }
            byte[] data = mReadQueue.peek();
            return data == null ? 0 : data.length;
        }
    }
}
