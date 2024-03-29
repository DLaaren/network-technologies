package ru.nsu.fit.vinter.lab5.SOCKSproxy.Proxy;

import ru.nsu.fit.vinter.lab5.SOCKSproxy.Exceptions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Arrays;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

// https://stackoverflow.com/questions/3895461/non-blocking-sockets

public class Proxy implements Runnable {
    private static final Logger logger = Logger.getLogger(Proxy.class.getName());
    private static final int BUFFER_SIZE = 1024;
    private static final int SOCKS_VERSION = 5;
    private static final int AUTH_TYPE = 0;
    private static final int ESTABLISH_CONNECTION_COMMAND = 1; // establish a TCP/IP stream connection
    ServerSocketChannel serverSocketChannel;
    Selector selector;

    private int proxyPort;

    public Proxy(int port) {
        try {
            this.proxyPort = port;
            SocketAddress socket = new InetSocketAddress(InetAddress.getByName("localhost"), this.proxyPort);
            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.bind(socket);
            selector = Selector.open();
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.register(selector, serverSocketChannel.validOps());
            logger.info("Proxy is listening on port " + this.proxyPort);

        } catch (UnknownHostException e) {
            logger.log(Level.SEVERE, "Error while getting socket port number");
            closeProxy();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error while creating resources");
            closeProxy();
        }
    }

    enum State {
        GREETING,
        // AUTHENTICATION_REQUEST,
        // AUTHENTICATION,
        CONNECTION,
        CONNECTED;
    }

    static class ConnectionContext {
        ByteBuffer in; // buffer for reading
        ByteBuffer out; //buffer for writing
        SelectionKey peer;
        State state;

        int addressType;
        int domainNameLength;
        InetAddress address;
        int port;
    }

    enum AddressType {
        IPv4(1),
        DomainName(3),
        IPv6(4);
        private final int type;

        AddressType(int type) {
            this.type = (type);
        }

        public int getAddressType() {
            return type;
        }
    }

    public void run() {
        while (true) {
            SelectionKey key = null;
            try {
                while (selector.select() > -1) {
                    Iterator<SelectionKey> selectedKeys = selector.selectedKeys().iterator();
                    while (selectedKeys.hasNext()) {
                        key = selectedKeys.next();
                        selectedKeys.remove();
                        if (!key.isValid()) {
                            continue;
                        }

                        if (key.isAcceptable()) {
                            SocketChannel newChannel = ((ServerSocketChannel) key.channel()).accept();
                            newChannel.configureBlocking(false);
                            newChannel.register(key.selector(), SelectionKey.OP_READ);
                            logger.info("Get connection request");
                        } else if (key.isReadable()) {
                            SocketChannel channel = ((SocketChannel) key.channel());
                            ConnectionContext connectionContext = ((ConnectionContext) key.attachment());

                            if (connectionContext == null) {
                                key.attach(connectionContext = new ConnectionContext());
                                connectionContext.in = ByteBuffer.allocate(BUFFER_SIZE);
                                connectionContext.out = ByteBuffer.allocate(BUFFER_SIZE);
                                connectionContext.state = State.GREETING;
                            }

                            connectionContext.in.clear();
                            int bytesRead = channel.read(connectionContext.in);
                            if (bytesRead == -1) {
                                channel.shutdownInput();
                                channel.register(selector, key.interestOps() & ~SelectionKey.OP_READ, connectionContext);

                                if (connectionContext.peer != null) {
                                    SocketChannel peerChannel = (SocketChannel) connectionContext.peer.channel();
                                    peerChannel.shutdownOutput();
                                    peerChannel.register(selector, peerChannel.keyFor(selector).interestOps() & ~SelectionKey.OP_WRITE, connectionContext.peer.attachment());

                                    logger.info("Error while reading shutting down sender input and receiver output");
                                }
                            } else if (connectionContext.state == State.GREETING) {
                                logger.info("Start reading greeting message");
                                processGreetingMessage(key, connectionContext);
                            } else if (connectionContext.state == State.CONNECTION) {
                                logger.info("Start reading connection message");
                                processConnectionMessage(key, connectionContext);
                            } else if (connectionContext.state == State.CONNECTED) {
                                key.interestOps(key.interestOps() ^ SelectionKey.OP_READ);
                                connectionContext.peer.interestOps(connectionContext.peer.interestOps() | SelectionKey.OP_WRITE);
                            }
                        } else if (key.isWritable()) {
                            SocketChannel channel = ((SocketChannel) key.channel());
                            ConnectionContext connectionContext = ((ConnectionContext) key.attachment());

                            connectionContext.out.flip();

                            int bytesWritten = channel.write(connectionContext.out);
                            if (bytesWritten == -1) {
                                closeKey(key);
                                logger.log(Level.WARNING, "Error while writing - closing the connection");
                            }
                            if (connectionContext.out.remaining() == 0) {
                                if (connectionContext.state == State.GREETING) {
                                    connectionContext.state = State.CONNECTION;
                                    key.interestOps(key.interestOps() | SelectionKey.OP_READ);
                                }

                                key.interestOps(key.interestOps() ^ SelectionKey.OP_WRITE);
                                if (connectionContext.state == State.CONNECTED && connectionContext.peer != null)
                                    connectionContext.peer.interestOps(connectionContext.peer.interestOps() | SelectionKey.OP_READ);
                            }
                        } else if (key.isConnectable()) {
                            logger.info("Trying to connect");
                            SocketChannel channel = ((SocketChannel) key.channel());
                            ConnectionContext connectionContext = ((ConnectionContext) key.attachment());

                            if (!channel.finishConnect()) {
                                throw new FailedConnectionException("Error at 'finishConnect'");
                            }

                            int addressType = connectionContext.addressType;
                            byte[] address;
                            if (addressType == AddressType.DomainName.getAddressType()) {
                                ByteBuffer tmp = ByteBuffer.allocate(connectionContext.domainNameLength + 1);
                                tmp.put((byte) connectionContext.domainNameLength);
                                tmp.put(connectionContext.address.getAddress());
                                address = tmp.array();
                            } else {
                                address = connectionContext.address.getAddress();
                            }

                            byte[] port = {((byte) (connectionContext.port >> 8)), ((byte) connectionContext.port)};
                            ByteBuffer connectionMessageReply = ByteBuffer.allocate(6 + address.length);
                            connectionMessageReply.put(new byte[]{5, 0, 0});
                            connectionMessageReply.put((byte) addressType);
                            connectionMessageReply.put(address);
                            connectionMessageReply.put(port);

                            ConnectionContext peerConnectionContext = (ConnectionContext) connectionContext.peer.attachment();
                            connectionContext.out = peerConnectionContext.in;
                            connectionContext.in = peerConnectionContext.out;

                            peerConnectionContext.out.clear();
                            peerConnectionContext.out.put(connectionMessageReply.array());

                            connectionContext.state = State.CONNECTED;
                            peerConnectionContext.state = State.CONNECTED;

                            key.interestOps(0);
                            connectionContext.peer.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);

                            logger.info("Connected successfully");
                        }
                    }
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "IOException at select :: " + e.getMessage());
            } catch (BadRequestException | UnsupportedAuthenticationCodeException | UnsupportedCommandCodeException |
                     WrongProtocolVersion | FailedConnectionException e) {
                logger.info(e.getMessage());
            } finally {
                try {
                    closeKey(key);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    void processGreetingMessage(SelectionKey key, ConnectionContext connectionContext) throws IOException, BadRequestException, WrongProtocolVersion, UnsupportedAuthenticationCodeException {
        byte[] GREETING_REPLY = new byte[] {5, 0};
        byte[] greetingMessageFromClient = connectionContext.in.array();

        if (greetingMessageFromClient[0] != SOCKS_VERSION) {
            logger.log(Level.SEVERE, "Got wrong protocol version");
            closeKey(key);
            throw new WrongProtocolVersion("Wrong protocol version");
        }
        else if (greetingMessageFromClient[2] != AUTH_TYPE) {
            logger.log(Level.SEVERE, "Got unsupported authentication code");
            closeKey(key);
            throw new UnsupportedAuthenticationCodeException("Unsupported authentication code");
        }
        else if (connectionContext.in.position() < 3) {
            logger.log(Level.SEVERE, "Bad request");
            closeKey(key);
            throw new BadRequestException("Bad request");
        }
        else {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            connectionContext.out.clear();
            connectionContext.out.put(GREETING_REPLY);

            logger.info("The greeting was successful");
        }
    }

    void processConnectionMessage(SelectionKey key, ConnectionContext connectionContext) throws BadRequestException, WrongProtocolVersion, UnsupportedCommandCodeException, FailedConnectionException, IOException {
        byte[] connectionMessageFromClient = connectionContext.in.array();

        int addressType = connectionMessageFromClient[3];
        int msgSize = 6;
        if (addressType == AddressType.IPv4.getAddressType()) {
            msgSize += 4;
        }
        else if (addressType == AddressType.DomainName.getAddressType()) {
            msgSize += 1 + connectionMessageFromClient[4];
        }
        else if (addressType == AddressType.IPv6.getAddressType()) {
            msgSize += 16;
        }
        else {
            logger.log(Level.SEVERE, "Bad request");
            closeKey(key);
            throw new BadRequestException("Bad request");
        }

        if (connectionMessageFromClient[0] != SOCKS_VERSION) {
            logger.log(Level.SEVERE, "Got wrong protocol version");
            closeKey(key);
            throw new WrongProtocolVersion("Wrong protocol version");
        }
        if (connectionMessageFromClient[1] != ESTABLISH_CONNECTION_COMMAND) {
            logger.log(Level.SEVERE, "Got unsupported command code");
            closeKey(key);
            throw new UnsupportedCommandCodeException("Unsupported command code");
        }
        if (connectionContext.in.position() < msgSize) {
            logger.log(Level.SEVERE, "Bad request");
            closeKey(key);
            throw new BadRequestException("Bad request");
        }
        else {
            InetAddress address;
            int port;

            logger.info("address type :: " + addressType);

            if (addressType == AddressType.IPv4.getAddressType()) {
                address = InetAddress.getByAddress(Arrays.copyOfRange(connectionMessageFromClient, 4, 8));
                port = (((0xFF & connectionMessageFromClient[8]) << 8) + (0xFF & connectionMessageFromClient[9]));
                logger.info("address :: " + address.getHostAddress());
            }

            else if (addressType == AddressType.DomainName.getAddressType()) {
                int domainNameLength = connectionMessageFromClient[4];
                byte[] domainName = Arrays.copyOfRange(connectionMessageFromClient, 5, 5 + domainNameLength);
                address = InetAddress.getByName(new String(domainName));
                port  = (((0xFF & (5 + domainNameLength)) << 8) + (0xFF & (5 + domainNameLength + 1)));
                logger.info("address :: " + new String(domainName));
            }

            else if (addressType == AddressType.IPv6.getAddressType()) {
                address = InetAddress.getByAddress(Arrays.copyOfRange(connectionMessageFromClient, 4, 20));
                port = (((0xFF & connectionMessageFromClient[20]) << 8) + (0xFF & connectionMessageFromClient[21]));
                logger.info("address :: " + address.getCanonicalHostName());
            }
            else {
                throw new BadRequestException("Bad request");
            }

            logger.info("port :: " + port);

            key.interestOps(0); // client waits until connection with host

            SocketChannel peer = SocketChannel.open();
            peer.configureBlocking(false);
            SelectionKey peerKey = peer.register(key.selector(), SelectionKey.OP_CONNECT);
            try {
                peer.connect(new InetSocketAddress(address, port));
            } catch (IOException e) {
                closeKey(key);
            }
            ConnectionContext peerConnectionContext = new ConnectionContext();
            peerConnectionContext.peer = key;
            peerKey.attach(peerConnectionContext);
            connectionContext.peer = peerKey;

            connectionContext.addressType = addressType;
            peerConnectionContext.addressType = addressType;

            connectionContext.address = address;
            peerConnectionContext.address = address;

            connectionContext.port = port;
            peerConnectionContext.port = port;

            if (addressType == AddressType.DomainName.getAddressType()) {
                connectionContext.domainNameLength = connectionMessageFromClient[4];
                peerConnectionContext.domainNameLength = connectionMessageFromClient[4];
            }
            logger.info("Connection request was process successfully");
        }
    }

    void closeKey(SelectionKey key) throws IOException {
        key.cancel();
        key.channel().close();
        SelectionKey peer = ((ConnectionContext) key.attachment()).peer;
        if (peer != null) {
            ((ConnectionContext) peer.attachment()).peer = null;
            if ((peer.interestOps() & SelectionKey.OP_WRITE) == 0) {
                ((ConnectionContext) peer.attachment()).in.flip();
            }
            peer.interestOps(SelectionKey.OP_WRITE);
        }
    }

    public void closeProxy() {
        logger.info("Closing proxy");
        try {
            for (SelectionKey key : selector.selectedKeys()) {
                closeKey(key);
            }
            serverSocketChannel.close();
            selector.close();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error while closing resources");
        }
    }
}
