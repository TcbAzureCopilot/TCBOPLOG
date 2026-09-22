package com.machineroom.auth;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import com.machineroom.config.AppConfig;

/**
 * SSLSocketFactory backed by the trust store named in {@code ldap.truststore}.
 * JNDI instantiates it through the static {@link #getDefault()} when
 * {@code java.naming.ldap.factory.socket} points at this class. Used only when a
 * custom trust store is configured; otherwise the JVM default trust store applies.
 */
public class TrustStoreSocketFactory extends SSLSocketFactory {

    private static volatile SSLSocketFactory delegate;
    private static volatile String delegateKey;

    public static synchronized SSLSocketFactory getDefault() {
        AppConfig cfg = AppConfig.get();
        String key = cfg.ldapTruststore() + "|" + cfg.ldapTruststoreType();
        if (delegate == null || !key.equals(delegateKey)) {
            try {
                KeyStore ks = KeyStore.getInstance(cfg.ldapTruststoreType());
                try (InputStream in = new FileInputStream(cfg.ldapTruststore())) {
                    char[] pw = cfg.ldapTruststorePassword().isEmpty() ? null : cfg.ldapTruststorePassword().toCharArray();
                    ks.load(in, pw);
                }
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ks);
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, tmf.getTrustManagers(), null);
                delegate = ctx.getSocketFactory();
                delegateKey = key;
            } catch (Exception e) {
                throw new IllegalStateException("無法載入 LDAP truststore: " + cfg.ldapTruststore(), e);
            }
        }
        return new TrustStoreSocketFactory();
    }

    private SSLSocketFactory d() {
        return delegate;
    }

    @Override public String[] getDefaultCipherSuites() { return d().getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return d().getSupportedCipherSuites(); }
    @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return d().createSocket(s, host, port, autoClose);
    }
    @Override public Socket createSocket(String host, int port) throws IOException { return d().createSocket(host, port); }
    @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return d().createSocket(host, port, localHost, localPort);
    }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException { return d().createSocket(host, port); }
    @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return d().createSocket(address, port, localAddress, localPort);
    }
    @Override public Socket createSocket() throws IOException { return d().createSocket(); }
}
