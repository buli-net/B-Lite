package wallet.network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import javax.net.SocketFactory;

/** Creates sockets tunneled through a configured SOCKS5 proxy. */
public final class Socks5SocketFactory extends SocketFactory {
    private final Proxy proxy;

    public Socks5SocketFactory(String proxyHost, int proxyPort) {
        if (!NetworkConfig.isLiteralIp(proxyHost) || proxyPort < 1 || proxyPort > 65535) {
            throw new IllegalArgumentException("Invalid numeric SOCKS5 proxy endpoint");
        }
        this.proxy = new Proxy(Proxy.Type.SOCKS,
                new InetSocketAddress(proxyHost, proxyPort));
    }

    @Override public Socket createSocket() {
        return new Socket(proxy);
    }

    @Override public Socket createSocket(String host, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override public Socket createSocket(String host, int port,
                                         InetAddress localHost, int localPort) throws IOException {
        Socket socket = createSocket();
        socket.bind(new InetSocketAddress(localHost, localPort));
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override public Socket createSocket(InetAddress address, int port,
                                         InetAddress localAddress, int localPort) throws IOException {
        Socket socket = createSocket();
        socket.bind(new InetSocketAddress(localAddress, localPort));
        socket.connect(new InetSocketAddress(address, port));
        return socket;
    }
}
