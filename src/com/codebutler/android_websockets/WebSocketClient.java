package com.codebutler.android_websockets;

import android.text.TextUtils;
import android.util.Base64;
import org.apache.http.*;
import org.apache.http.message.BasicLineParser;
import org.apache.http.message.BasicNameValuePair;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URI;
import java.util.List;

public class WebSocketClient {
    private static final String TAG = "WebSocketClient";

    private URI                      mURI;
    private Handler                  mHandler;
    private Socket                   mSocket;
    private Thread                   mThread;
    private List<BasicNameValuePair> mExtraHeaders;
    private HybiParser               mParser;

    private final Object mSendLock = new Object();

    public WebSocketClient(URI uri, Handler handler, List<BasicNameValuePair> extraHeaders) {
        mURI          = uri;
        mHandler      = handler;
        mExtraHeaders = extraHeaders;
        mParser       = new HybiParser(this);
    }

    public Handler getHandler() {
        return mHandler;
    }

    public void connect() {
        if (mThread != null && mThread.isAlive()) {
            return;
        }

        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int port = (mURI.getPort() != -1) ? mURI.getPort() : (mURI.getScheme().equals("wss") ? 443 : 80);

                    String path = TextUtils.isEmpty(mURI.getPath()) ? "/" : mURI.getPath();

                    String originScheme = mURI.getScheme().equals("wss") ? "https" : "http";
                    URI origin = new URI(originScheme, mURI.getSchemeSpecificPart(), null);

                    SocketFactory factory = SSLSocketFactory.getDefault();
                    mSocket = factory.createSocket(mURI.getHost(), port);

                    PrintWriter out = new PrintWriter(mSocket.getOutputStream());
                    out.print("GET " + path + " HTTP/1.1\r\n");
                    out.print("Upgrade: websocket\r\n");
                    out.print("Connection: Upgrade\r\n");
                    out.print("Host: " + mURI.getHost() + "\r\n");
                    out.print("Origin: " + origin.toString() + "\r\n");
                    out.print("Sec-WebSocket-Key: " + createSecret() + "\r\n");
                    out.print("Sec-WebSocket-Version: 13\r\n");
                    if (mExtraHeaders != null) {
                        for (NameValuePair pair : mExtraHeaders) {
                            out.print(String.format("%s: %s\r\n", pair.getName(), pair.getValue()));
                        }
                    }
                    out.print("\r\n");
                    out.flush();

                    InputStreamReader reader = new InputStreamReader(mSocket.getInputStream());

                    // Read HTTP response status line.
                    StatusLine statusLine = parseStatusLine(readLine(reader));
                    if (statusLine.getStatusCode() != HttpStatus.SC_SWITCHING_PROTOCOLS) {
                        throw new ProtocolException("Bad HTTP response: " + statusLine);
                    }

                    // Read HTTP response headers.
                    String line;
                    while (!TextUtils.isEmpty(line = readLine(reader))) {
                        Header header = parseHeader(line);
                        if (header.getName().equals("Sec-WebSocket-Accept")) {
                            // FIXME: Verify the response...
                        }
                    }

                    mHandler.onConnect();

                    // Now decode websocket frames.
                    mParser.start(mSocket);

                } catch (Exception ex) {
                    mHandler.onError(ex);
                }
            }
        });
        mThread.start();
    }

    public void disconnect() throws IOException {
        mSocket.close();
    }

    public void send(String data) {
        sendFrame(mParser.frame(data));
    }

    public void send(byte[] data) {
        sendFrame(mParser.frame(data));
    }

    private StatusLine parseStatusLine(String line) {
        return BasicLineParser.parseStatusLine(line, new BasicLineParser());
    }

    private Header parseHeader(String line) {
        return BasicLineParser.parseHeader(line,  new BasicLineParser());
    }

    // Can't use BufferedReader because it buffers past the HTTP data.
    private String readLine(InputStreamReader reader) throws IOException {
        int readChar = reader.read();
        if (readChar == -1) {
            return null;
        }
        StringBuilder string = new StringBuilder("");
        while (readChar != -1 && readChar != '\n') {
            if (readChar != '\r') {
                string.append((char) readChar);
            }
            readChar = reader.read();
        }
        return string.toString();
    }

    private String createSecret() {
        byte[] nonce = new byte[16];
        for (int i = 0; i < 16; i++) {
            nonce[i] = (byte) (Math.random() * 256);
        }
        return Base64.encodeToString(nonce, Base64.DEFAULT).trim();
    }

    void sendFrame(byte[] frame) {
        try {
            synchronized (mSendLock) {
                OutputStream outputStream = mSocket.getOutputStream();
                outputStream.write(frame);
                outputStream.flush();
            }
        } catch (IOException e) {
            mHandler.onError(e);
        }
    }

    public interface Handler {
        public void onConnect();
        public void onMessage(String message);
        public void onMessage(byte[] data);
        public void onDisconnect(int code, String reason);
        public void onError(Exception error);
    }
}