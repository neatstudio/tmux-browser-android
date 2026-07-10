package com.neatstudio.tmuxandroid;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.net.ssl.SSLSocketFactory;

final class AppEventSocketClient {
    private static final long HEARTBEAT_INTERVAL_MS = 15000L;
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 10000;
    private static final int SOCKET_READ_TIMEOUT_MS = 45000;

    interface Listener {
        void onMessage(String text);
        void onClosed();
    }

    private final Listener listener;
    private final Object writeLock = new Object();
    private Socket socket;
    private BufferedInputStream input;
    private BufferedOutputStream output;
    private volatile boolean closed;
    private Thread heartbeatThread;

    AppEventSocketClient(Listener listener) {
        this.listener = listener;
    }

    void connect(String baseUrl) {
        closed = false;
        new Thread(() -> run(baseUrl), "app-events-ws").start();
    }

    void close() {
        closed = true;
        try {
            sendFrame(8, new byte[0]);
        } catch (Exception ignored) {
        }
        closeSocketQuietly();
    }

    boolean isClosed() {
        return closed;
    }

    private void run(String baseUrl) {
        try {
            URI uri = buildWsUri(baseUrl);
            socket = openSocket(uri);
            input = new BufferedInputStream(socket.getInputStream());
            output = new BufferedOutputStream(socket.getOutputStream());
            handshake(uri);
            startHeartbeat();
            while (!closed) {
                Frame frame = readFrame();
                if (frame.opcode == 1) {
                    listener.onMessage(new String(frame.payload, StandardCharsets.UTF_8));
                } else if (frame.opcode == 8) {
                    return;
                } else if (frame.opcode == 9) {
                    sendFrame(10, frame.payload);
                }
            }
        } catch (Exception ignored) {
        } finally {
            closed = true;
            listener.onClosed();
            closeSocketQuietly();
        }
    }

    private URI buildWsUri(String baseUrl) throws Exception {
        URI base = new URI(baseUrl);
        String scheme = "https".equalsIgnoreCase(base.getScheme()) ? "wss" : "ws";
        int port = base.getPort();
        String authority = port == -1 ? base.getHost() : base.getHost() + ":" + port;
        return new URI(scheme + "://" + authority + "/ws/events");
    }

    private Socket openSocket(URI uri) throws Exception {
        int port = uri.getPort();
        if (port == -1) {
            port = "wss".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(uri.getHost(), port), SOCKET_CONNECT_TIMEOUT_MS);
        Socket connected;
        if ("wss".equalsIgnoreCase(uri.getScheme())) {
            connected = SSLSocketFactory.getDefault().createSocket(raw, uri.getHost(), port, true);
        } else {
            connected = raw;
        }
        connected.setKeepAlive(true);
        connected.setTcpNoDelay(true);
        connected.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
        return connected;
    }

    private void startHeartbeat() {
        heartbeatThread = new Thread(() -> {
            while (!closed) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                    if (!closed) {
                        sendFrame(9, new byte[0]);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception error) {
                    closeSocketQuietly();
                    return;
                }
            }
        }, "app-events-ws-heartbeat");
        heartbeatThread.start();
    }

    private void handshake(URI uri) throws Exception {
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
        String host = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
        String request = "GET " + uri.getRawPath() + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();
        String response = readHttpHeaders();
        if (!response.startsWith("HTTP/1.1 101") && !response.startsWith("HTTP/1.0 101")) {
            throw new IllegalStateException("WebSocket handshake failed");
        }
        String expected = websocketAccept(key).toLowerCase(java.util.Locale.US);
        if (!response.toLowerCase(java.util.Locale.US).contains("sec-websocket-accept: " + expected)) {
            throw new IllegalStateException("WebSocket accept header mismatch");
        }
    }

    private String readHttpHeaders() throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        int a = -1;
        int b = -1;
        int c = -1;
        int d;
        while ((d = input.read()) != -1) {
            buffer.write(d);
            if (a == '\r' && b == '\n' && c == '\r' && d == '\n') {
                break;
            }
            a = b;
            b = c;
            c = d;
        }
        return buffer.toString("US-ASCII");
    }

    private static String websocketAccept(String key) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        return Base64.encodeToString(
                digest.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)),
                Base64.NO_WRAP
        );
    }

    private Frame readFrame() throws Exception {
        int first = input.read();
        int second = input.read();
        if (first == -1 || second == -1) {
            throw new IllegalStateException("WebSocket closed");
        }
        int opcode = first & 0x0f;
        long length = second & 0x7f;
        if (length == 126) {
            length = ((long) input.read() << 8) | input.read();
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | input.read();
            }
        }
        byte[] payload = readExactly((int) length);
        return new Frame(opcode, payload);
    }

    private byte[] readExactly(int length) throws Exception {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(data, offset, length - offset);
            if (read == -1) {
                throw new IllegalStateException("WebSocket closed");
            }
            offset += read;
        }
        return data;
    }

    private void sendFrame(int opcode, byte[] payload) throws Exception {
        synchronized (writeLock) {
            if (output == null) {
                return;
            }
            output.write(0x80 | opcode);
            byte[] mask = new byte[4];
            new SecureRandom().nextBytes(mask);
            int length = payload.length;
            if (length < 126) {
                output.write(0x80 | length);
            } else if (length <= 0xffff) {
                output.write(0x80 | 126);
                output.write((length >>> 8) & 0xff);
                output.write(length & 0xff);
            } else {
                output.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) {
                    output.write((length >>> (8 * i)) & 0xff);
                }
            }
            output.write(mask);
            byte[] masked = Arrays.copyOf(payload, payload.length);
            for (int i = 0; i < masked.length; i++) {
                masked[i] = (byte) (masked[i] ^ mask[i % 4]);
            }
            output.write(masked);
            output.flush();
        }
    }

    private void closeSocketQuietly() {
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            heartbeatThread = null;
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static final class Frame {
        final int opcode;
        final byte[] payload;

        Frame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }
}
