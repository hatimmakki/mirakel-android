package de.azapps.mirakel.sync.taskwarrior.network_helper;

import android.util.Base64;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringBufferInputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.KeyFactory;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import de.azapps.tools.Log;

public class TLSClient {
    public static class NoSuchCertificateException extends Exception {
        private static final long serialVersionUID = -4606663552584336235L;

    }

    private static final String TAG = "TLSClient";

    private static List<X509Certificate> generateCertificateFromPEM(final String cert)
    throws  NoSuchCertificateException {
        if (cert == null) {
            throw new NoSuchCertificateException();
        }
        final String[] parts  = cert.split("-----END CERTIFICATE-----");
        final List<X509Certificate> certs = new ArrayList<>(parts.length);
        for (final String part : parts) {
            if (part.trim().isEmpty()) {
                continue;
            }
            try {
                certs.add((X509Certificate) CertificateFactory.getInstance("X.509")
                          .generateCertificate(new StringBufferInputStream(part.trim() + "\n-----END CERTIFICATE-----")));
            } catch (final CertificateException e) {
                Log.wtf(TAG, "parsing failed:" + part, e);
                return certs;
            }
        }
        return certs;
    }

    private static RSAPrivateKey generatePrivateKeyFromPEM(final String key)
    throws ParseException {
        final byte[] keyBytes = parseDERFromPEM(key,
                                                "-----BEGIN RSA PRIVATE KEY-----",
                                                "-----END RSA PRIVATE KEY-----");
        final PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        final KeyFactory factory;
        try {
            factory = KeyFactory.getInstance("RSA", "BC");
        } catch (final NoSuchAlgorithmException e) {
            Log.e(TAG, "RSA-Algorithm not found", e);
            return null;
        } catch (final NoSuchProviderException e) {
            Log.e(TAG, "BC not found", e);
            return null;
        }
        try {
            return (RSAPrivateKey) factory.generatePrivate(spec);
        } catch (final InvalidKeySpecException e) {
            Log.e(TAG, "cannot parse key", e);
            return null;
        }
    }

    private static byte[] parseDERFromPEM(final String pem,
                                          final String beginDelimiter, final String endDelimiter)
    throws ParseException {
        Log.w(TAG, pem);
        String[] tokens = pem.split(beginDelimiter);
        if (tokens.length < 2) {
            Log.wtf(TAG, pem);
            throw new ParseException("Wrong PEM format", 0);
        }
        tokens = tokens[1].split(endDelimiter);
        try {
            return Base64.decode(tokens[0], Base64.NO_PADDING);
        } catch (final IllegalArgumentException e) {
            Log.e(TAG, "bad base-64", e);
            throw new ParseException("bad base-64", 0);
        }
    }

    private SSLSocket _socket;

    private InputStream in;

    private OutputStream out;

    private javax.net.ssl.SSLSocketFactory sslFact;

    // //////////////////////////////////////////////////////////////////////////////
    public TLSClient() {
        this._socket = null;
        this.sslFact = null;
        this.in = null;
        this.out = null;
    }

    public void close() {
        if (this._socket == null) {
            Log.e(TAG, "socket null");
            return;
        }
        try {
            this.out.flush();
            this.in.close();
            this.out.close();
            this._socket.close();
            this._socket = null;
        } catch (final IOException e) {
            Log.e(TAG, "Cannot close Socket", e);
        } catch (final NullPointerException e) {
            Log.e(TAG,
                  "Nullpointer, means there was no established connection", e);
        }
    }



    // //////////////////////////////////////////////////////////////////////////////
    public void connect(final String host, final int port) throws IOException {
        Log.i(TAG, "connect");
        if (this._socket != null) {
            try {
                this._socket.close();
            } catch (final IOException e) {
                Log.e(TAG, "cannot close socket", e);
            }
        }
        try {
            Log.d(TAG, "connected to " + host + ':' + port);
            this._socket = (SSLSocket) this.sslFact.createSocket();
            this._socket.setUseClientMode(true);
            this._socket.setEnableSessionCreation(true);
            this._socket.setNeedClientAuth(true);
            this._socket.setTcpNoDelay(true);
            this._socket.connect(new InetSocketAddress(host, port));
            this._socket.startHandshake();
            this.out = this._socket.getOutputStream();
            this.in = this._socket.getInputStream();
            Log.d(TAG, "connected to " + host + ':' + port);
            return;
        } catch (final UnknownHostException e) {
            Log.e(TAG, "Unknown Host", e);
        } catch (final ConnectException e) {
            Log.e(TAG, "Cannot connect to Host", e);
        } catch (final SocketException e) {
            Log.e(TAG, "IO Error", e);
        }
        throw new IOException();
    }

    // //////////////////////////////////////////////////////////////////////////////
    public void init(final String root, final String user_ca,
                     final String user_key) throws ParseException, CertificateException,
        NoSuchCertificateException {
        Log.i(TAG, "init");
        try {

            List<X509Certificate> ROOT = generateCertificateFromPEM(root);
            X509Certificate USER_CERT = (X509Certificate) CertificateFactory.getInstance("X.509")
                                        .generateCertificate(new StringBufferInputStream(user_ca));
            final RSAPrivateKey USER_KEY = generatePrivateKeyFromPEM(user_key);
            final KeyStore trusted = KeyStore.getInstance(KeyStore
                                     .getDefaultType());
            trusted.load(null);
            for (X509Certificate aROOT : ROOT) {
                trusted.setCertificateEntry("taskwarrior-ROOT", aROOT);
            }
            trusted.setCertificateEntry("taskwarrior-USER", USER_CERT);
            ROOT.add(USER_CERT);
            final Certificate[] chain = ROOT.toArray(new Certificate[ROOT.size()]);//{ USER_CERT., ROOT };
            final KeyManagerFactory keyManagerFactory = KeyManagerFactory
                    .getInstance(KeyManagerFactory.getDefaultAlgorithm());
            // Hack to get it working on android 2.2
            final String pwd = "secret";
            trusted.setEntry("user", new KeyStore.PrivateKeyEntry(USER_KEY,
                             chain), new KeyStore.PasswordProtection(pwd.toCharArray()));
            keyManagerFactory.init(trusted, pwd.toCharArray());
            final SSLContext context = SSLContext.getInstance("TLS");
            final TrustManagerFactory tmf = TrustManagerFactory
                                            .getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trusted);
            final TrustManager[] trustManagers = tmf.getTrustManagers();
            context.init(keyManagerFactory.getKeyManagers(), trustManagers,
                         new SecureRandom());
            this.sslFact = context.getSocketFactory();
        } catch (final UnrecoverableKeyException e) {
            Log.w(TAG, "cannot restore key");
            throw new CertificateException(e);
        } catch (final KeyManagementException e) {
            Log.w(TAG, "cannot access key");
            throw new CertificateException(e);
        } catch (final KeyStoreException e) {
            Log.w(TAG, "cannot handle keystore");
            throw new CertificateException(e);
        } catch (final NoSuchAlgorithmException e) {
            Log.w(TAG, "no matching algorithm found");
            throw new CertificateException(e);
        } catch (final CertificateException e) {
            Log.w(TAG, "certificat not readable");
            throw new CertificateException(e);
        } catch (final IOException e) {
            Log.w(TAG, "general io problem");
            throw new CertificateException(e);
        }
    }

    // //////////////////////////////////////////////////////////////////////////////
    public String recv() {
        Log.i(TAG, "reveive data from " + this._socket.getLocalAddress() + ':'
              + this._socket.getLocalPort());
        if (!this._socket.isConnected()) {
            Log.e(TAG, "not connected");
            return null;
        }
        try {
            final byte[] header = new byte[4];
            this.in.read(header);
            final Scanner scanner = new Scanner(this.in);
            final Scanner s = scanner.useDelimiter("\\A");
            final String result = s.hasNext() ? s.next() : "";
            s.close();
            scanner.close();
            return result;
        } catch (final IOException e) {
            Log.e(TAG, "cannot read Inputstream", e);
        }
        return null;
    }

    // //////////////////////////////////////////////////////////////////////////////
    public void send(final String data) {
        final DataOutputStream dos = new DataOutputStream(this.out);
        Log.i(TAG, "send data");
        if (!this._socket.isConnected()) {
            Log.e(TAG, "socket not connected");
            return;
        }
        try {
            dos.writeInt(data.getBytes().length);
            dos.write(data.getBytes());
        } catch (final IOException e) {
            Log.e(TAG, "cannot write data to outputstream", e);
        }
        try {
            dos.flush();
            dos.close();
            this.out.flush();
        } catch (final IOException e) {
            Log.e(TAG, "cannot flush data to outputstream", e);
        }
    }
}
