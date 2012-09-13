package com.codebutler.android_websockets;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashSet;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.net.http.AndroidHttpClient;
import android.os.Looper;

public class SocketIOClient {
    public static interface Handler {
        public void onConnect();

        public void on(String event, JSONArray arguments);

        public void onDisconnect(int code, String reason);

        public void onError(Exception error);
    }

    URI mURI;
    Handler mHandler;
    String mSession;
    int mHeartbeat;
    int mClosingTimeout;
    WebSocketClient mClient;

    public SocketIOClient(URI uri, Handler handler) {
        mURI = uri;
        mHandler = handler;
    }

    private static String downloadUriAsString(final HttpUriRequest req) throws IOException, NoSuchAlgorithmException, KeyManagementException {

        URL url = new URL(req.getURI().toString());
        URLConnection con = url.openConnection();
        Reader reader = new InputStreamReader(con.getInputStream());
        String s = "";
        while (true) {
            int ch = reader.read();
            if (ch==-1) {
                break;
            }
            s += (char)ch;
        }
        
        return s;
        
        /*AndroidHttpClient client = AndroidHttpClient.newInstance("android-websockets");
        try {
            HttpResponse res = client.execute(req);
            return readToEnd(res.getEntity().getContent());
        }
        finally {
            client.close();
        }*/
    }

    private static byte[] readToEndAsArray(InputStream input) throws IOException {
        DataInputStream dis = new DataInputStream(input);
        byte[] stuff = new byte[1024];
        ByteArrayOutputStream buff = new ByteArrayOutputStream();
        int read = 0;
        while ((read = dis.read(stuff)) != -1) {
            buff.write(stuff, 0, read);
        }

        return buff.toByteArray();
    }

    private static String readToEnd(InputStream input) throws IOException {
        return new String(readToEndAsArray(input));
    }

    android.os.Handler mSendHandler;
    Looper mSendLooper;

    public void emit(String name, JSONArray args) throws JSONException {
        final JSONObject event = new JSONObject();
        event.put("name", name);
        event.put("args", args);
        System.out.println(event.toString());
        mSendHandler.post(new Runnable() {
            @Override
            public void run() {
                mClient.send(String.format("5:::%s", event.toString()));
            }
        });
    }

    private void connectSession() throws URISyntaxException {
    	String StringUri = mURI.toString();
    	if( StringUri.contains("http") ) StringUri = StringUri.replace("http", "ws");
    	if( StringUri.contains("https") ) StringUri = StringUri.replace("https", "wss");
        mClient = new WebSocketClient(new URI(StringUri + "/socket.io/1/websocket/" + mSession), new WebSocketClient.Handler() {
            @Override
            public void onMessage(byte[] data) {
                cleanup();
                mHandler.onError(new Exception("Unexpected binary data"));
            }

            @Override
            public void onMessage(String message) {
                try {
                    System.out.println(message);
                    String[] parts = message.split(":", 4);
                    int code = Integer.parseInt(parts[0]);
                    switch (code) {
                    case 1:
                        onConnect();
                        break;
                    case 2:
                        // heartbeat
                        break;
                    case 3:
                        // message
                    case 4:
                        // json message
                        throw new Exception("message type not supported");
                    case 5: {
                        final String messageId = parts[1];
                        final String dataString = parts[3];
                        JSONObject data = new JSONObject(dataString);
                        String event = data.getString("name");
                        JSONArray args = data.getJSONArray("args");
                        if (!"".equals(messageId)) {
                            mSendHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    mClient.send(String.format("6:::%s", messageId));
                                }
                            });
                        }
                        mHandler.on(event, args);
                        break;
                    }
                    case 6:
                        // ACK
                        break;
                    case 7:
                        // error
                        throw new Exception(message);
                    case 8:
                        // noop
                        break;
                    default:
                        throw new Exception("unknown code");
                    }
                }
                catch (Exception ex) {
                    cleanup();
                    onError(ex);
                }
            }

            @Override
            public void onError(Exception error) {
                cleanup();
                mHandler.onError(error);
            }

            @Override
            public void onDisconnect(int code, String reason) {
                cleanup();
                // attempt reconnect with same session?
                mHandler.onDisconnect(code, reason);
            }

            @Override
            public void onConnect() {
                mSendHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mSendHandler.postDelayed(this, mHeartbeat);
                        mClient.send("2:::");
                    }
                }, mHeartbeat);
				mHandler.onConnect();
            }
        }, null);
        mClient.connect();
    }

    public void disconnect() throws IOException {
        cleanup();
    }

    private void cleanup() {
        try {
        	if( mClient != null ){
	            mClient.disconnect();
	            mClient = null;
        	}
        }
        catch (IOException e) {
        	mClient = null;
        }
        if( mSendLooper != null ){
	        mSendLooper.quit();
	        mSendLooper = null;
	        mSendHandler = null;
        }
    }

    public void connect() {
        if (mClient != null)
            return;
        new Thread() {
            public void run() {
                HttpPost post = new HttpPost(mURI.toString() + "/socket.io/1/");
                try {
                    String line = downloadUriAsString(post);
                    String[] parts = line.split(":");
                    mSession = parts[0];
                    String heartbeat = parts[1];
                    if (!"".equals(heartbeat))
                        mHeartbeat = Integer.parseInt(heartbeat) / 2 * 1000;
                    String transportsLine = parts[3];
                    String[] transports = transportsLine.split(",");
                    HashSet<String> set = new HashSet<String>(Arrays.asList(transports));
                    if (!set.contains("websocket"))
                        throw new Exception("websocket not supported");

                    Looper.prepare();
                    mSendLooper = Looper.myLooper();
                    mSendHandler = new android.os.Handler();

                    connectSession();

                    Looper.loop();
                }
                catch (Exception e) {
                    mHandler.onError(e);
                }
            };
        }.start();
    }
}
