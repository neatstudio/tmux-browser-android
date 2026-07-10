package com.neatstudio.tmuxandroid;

import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import javax.net.ssl.SSLSocketFactory;

final class TerminalSocketClient {
    private static final long HEARTBEAT_INTERVAL_MS = 15000L;
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 10000;
    private static final int SOCKET_READ_TIMEOUT_MS = 45000;

    interface Listener {
        void onConnected();
        void onOutput(String data);
        void onError(String message);
        void onClosed();
    }

    private final Object writeLock = new Object();
    private final Listener listener;
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "terminal-ws-write"));
    private Socket socket;
    private BufferedInputStream input;
    private BufferedOutputStream output;
    private volatile boolean closed;
    private Thread thread;
    private Thread heartbeatThread;

    TerminalSocketClient(Listener listener) {
        this.listener = listener;
    }

    void connect(String baseUrl, String sessionName, int cols, int rows) {
        closed = false;
        thread = new Thread(() -> run(baseUrl, sessionName, cols, rows), "terminal-ws");
        thread.start();
    }

    void sendInput(String data) {
        sendMessageAsync("input", "data", data);
    }

    void resize(int cols, int rows) {
        sendMessageAsync("resize", "cols", cols, "rows", rows);
    }

    void scroll(int lines) {
        sendMessageAsync("scroll", "lines", lines);
    }

    void clearHistory() {
        sendMessageAsync("clear-history");
    }

    void close() {
        closed = true;
        try {
            writeExecutor.execute(() -> {
                try {
                    sendFrame(8, new byte[0]);
                } catch (Exception ignored) {
                }
                closeSocketQuietly();
            });
            writeExecutor.shutdown();
        } catch (RejectedExecutionException ignored) {
            closeSocketQuietly();
        }
    }

    boolean isClosed() {
        return closed;
    }

    private void run(String baseUrl, String sessionName, int cols, int rows) {
        try {
            URI uri = buildWsUri(baseUrl);
            socket = openSocket(uri);
            input = new BufferedInputStream(socket.getInputStream());
            output = new BufferedOutputStream(socket.getOutputStream());
            handshake(uri);
            sendMessageSync(
                    "attach",
                    "tabId", "android-" + System.currentTimeMillis(),
                    "sessionName", sessionName,
                    "cols", cols,
                    "rows", rows
            );
            listener.onConnected();
            startHeartbeat();
            readLoop();
        } catch (Exception error) {
            if (!closed) {
                listener.onError(error.getMessage() == null ? error.toString() : error.getMessage());
            }
        } finally {
            closed = true;
            listener.onClosed();
            closeSocketQuietly();
            writeExecutor.shutdownNow();
        }
    }

    private URI buildWsUri(String baseUrl) throws Exception {
        URI base = new URI(baseUrl);
        String scheme = "https".equalsIgnoreCase(base.getScheme()) ? "wss" : "ws";
        int port = base.getPort();
        String authority = port == -1 ? base.getHost() : base.getHost() + ":" + port;
        return new URI(scheme + "://" + authority + "/ws/terminal");
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
            connected = ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, uri.getHost(), port, true);
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
        }, "terminal-ws-heartbeat");
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
            throw new IllegalStateException("WebSocket handshake failed: " + response.split("\r\n")[0]);
        }
        String expected = websocketAccept(key);
        if (!response.toLowerCase(java.util.Locale.US).contains("sec-websocket-accept: " + expected.toLowerCase(java.util.Locale.US))) {
            throw new IllegalStateException("WebSocket accept header mismatch");
        }
    }

    private String readHttpHeaders() throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        int current;
        while ((current = input.read()) != -1) {
            buffer.write(current);
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') {
                break;
            }
            previous3 = previous2;
            previous2 = previous1;
            previous1 = current;
        }
        return buffer.toString("US-ASCII");
    }

    private static String websocketAccept(String key) throws Exception {
        String source = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        return Base64.encodeToString(digest.digest(source.getBytes(StandardCharsets.US_ASCII)), Base64.NO_WRAP);
    }

    private void readLoop() throws Exception {
        while (!closed) {
            Frame frame = readFrame();
            if (frame.opcode == 1) {
                handleText(new String(frame.payload, StandardCharsets.UTF_8));
            } else if (frame.opcode == 8) {
                return;
            } else if (frame.opcode == 9) {
                sendFrame(10, frame.payload);
            }
        }
    }

    private Frame readFrame() throws Exception {
        int first = input.read();
        int second = input.read();
        if (first == -1 || second == -1) {
            throw new IllegalStateException("WebSocket closed");
        }
        int opcode = first & 0x0f;
        boolean masked = (second & 0x80) != 0;
        long length = second & 0x7f;
        if (length == 126) {
            length = ((long) input.read() << 8) | input.read();
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | input.read();
            }
        }
        byte[] mask = null;
        if (masked) {
            mask = readExactly(4);
        }
        byte[] payload = readExactly((int) length);
        if (masked && mask != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ mask[i % 4]);
            }
        }
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

    private void handleText(String text) throws Exception {
        JSONObject message = new JSONObject(text);
        String type = message.optString("type", "");
        if ("output".equals(type)) {
            listener.onOutput(message.optString("data", ""));
        } else if ("error".equals(type)) {
            listener.onError(message.optString("message", "Terminal error"));
        } else if ("session-exit".equals(type)) {
            close();
        }
    }

    private void sendJsonSync(JSONObject object) {
        try {
            sendFrame(1, object.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            if (!closed) {
                listener.onError(error.getMessage() == null ? error.toString() : error.getMessage());
            }
        }
    }

    private void sendMessageAsync(String type, Object... keyValues) {
        try {
            writeExecutor.execute(() -> sendMessageSync(type, keyValues));
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void sendMessageSync(String type, Object... keyValues) {
        try {
            JSONObject object = new JSONObject();
            object.put("type", type);
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                object.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
            sendJsonSync(object);
        } catch (Exception error) {
            if (!closed) {
                listener.onError(error.getMessage() == null ? error.toString() : error.getMessage());
            }
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

    private static final class Frame {
        final int opcode;
        final byte[] payload;

        Frame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }
}
