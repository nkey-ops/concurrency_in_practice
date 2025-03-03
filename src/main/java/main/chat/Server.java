package main.chat;

import static java.util.Objects.requireNonNull;

import main.chat.Server.HttpRequest.HttpMethod;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class Server implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(Server.class.getName());

    private final int CONNECTION_POOL_SIZE = 1;
    private final int CLIENT_POOL_SIZE = 1;

    // Socket Management
    private final ThreadPoolExecutor clientPool =
            new ThreadPoolExecutor(
                    CLIENT_POOL_SIZE,
                    CLIENT_POOL_SIZE,
                    60,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(CONNECTION_POOL_SIZE),
                    new ThreadPoolExecutor.CallerRunsPolicy());
    private boolean isStarted;
    private boolean isClosed;
    private Optional<Thread> portListener = Optional.empty();

    private final BlockingQueue<ChatMessage> chatMessages = new LinkedBlockingQueue<>();
    private final BlockingQueue<User> chatUsers = new LinkedBlockingQueue<>();

    // HTTP management
    private static final Set<String> requestTargets = Set.of("/messages");

    public Server() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }

    public synchronized void start() {
        if (isStarted) {
            throw new IllegalStateException("Server has already been started");
        }

        try {
            var serverSocket = new ServerSocket(8800);
            serverSocket.setReuseAddress(true);
            LOG.info("Starting: " + serverSocket);

            portListener = Optional.of(getPortListener(serverSocket));
            portListener.get().start();
        } catch (Exception e) {
            e.printStackTrace();
        }

        isStarted = true;
    }

    public synchronized void waitTillStop() throws InterruptedException {
        if (!isStarted) {
            throw new IllegalStateException("Server hasn't been started");
        }
        if (isClosed) {
            throw new IllegalStateException("Server has been closed");
        }

        wait();
    }

    @Override
    public synchronized void close() {
        if (!isStarted) {
            throw new IllegalStateException("Server hass'n been started");
        }
        if (isClosed) {
            throw new IllegalStateException("Server has already been closed");
        }

        isClosed = true;

        System.out.println("Clean Up: " + this);
        cleanUpPortListener();
        cleanUpConnectionPool();
        System.out.println("Cleaned Up: " + this);

        notifyAll();
    }

    private synchronized void cleanUpPortListener() {
        if (portListener.isPresent()) {
            Thread thread = portListener.get();
            thread.interrupt();
            portListener = Optional.empty();

            System.out.println("Stopping Connection Pool Thread: " + thread);

            try {
                thread.join(2000);
            } catch (Exception e) {
            } // ignoring

            if (thread.getState() != Thread.State.TERMINATED) {
                System.out.println(
                        "Couldn't shutdown on hook"
                                + " thread: "
                                + thread
                                + " "
                                + thread.getState());
            } else {
                System.out.println("Stoped Connection Pool" + " Thread: " + thread);
            }
        }
    }

    private synchronized void cleanUpConnectionPool() {
        System.out.println("Closing Connection Pool: " + clientPool);

        try {
            var notStartedClients = clientPool.shutdownNow();

            // running not ran tasks with an interrupt status, so all the opened sockets are
            // closed
            Thread.currentThread().interrupt();
            notStartedClients.forEach(Runnable::run);
            Thread.interrupted();

            if (clientPool.awaitTermination(10, TimeUnit.SECONDS)) {
                LOG.warning("Couldn't shutdown Client Pool. Timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warning("Couldn't shutdown the Client Pool, was interrupted");
            return;
        }
        System.out.println("Closed all the connections");
    }

    private Thread getPortListener(ServerSocket serverSocket) {
        requireNonNull(serverSocket);

        return new Thread() {
            public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        var clientSocket = serverSocket.accept();
                        clientPool.submit(() -> handleConnection(clientSocket));
                    } catch (SocketException e) {
                        if (Thread.currentThread().isInterrupted()) {
                            LOG.info("Socket was interrupted");
                        } else {
                            LOG.severe(e.toString());
                        }
                    } catch (Exception e) {
                        LOG.severe(e.toString());
                    }
                }
            }

            public void interrupt() {
                try {
                    serverSocket.close();
                } catch (Exception e) {
                    LOG.warning(e.toString());
                } finally {
                    super.interrupt();
                }
            }
        };
    }

    private void handleConnection(Socket clientSocket) {
        requireNonNull(clientSocket);
        var socketName = "[%s:%s]".formatted(clientSocket.getInetAddress(), clientSocket.getPort());
        try (var reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                var writer = new PrintWriter(clientSocket.getOutputStream());
                var sock = clientSocket) {

            LOG.info("Connected: " + sock);

            clientSocket.setSoTimeout((int) Duration.ofMinutes(15).toMillis());

            String firstLine = null;
            while (!Thread.currentThread().isInterrupted()
                    && (firstLine = reader.readLine()) != null) {

                System.out.println("hi");
                var httpRequest = parseRequest(firstLine, reader);
                System.out.println(httpRequest);

                // var list = new LinkedList<String>();
                // list.add(message);
                // message = null;
                // while (reader.ready()) {
                // list.add(reader.readLine());
                // }

                // System.out.printf("%s:> %s%n", socketName, list);
                // System.out.printf("Responsed: [%s]> %s%n", sock, message);
                // writer.println("Received!");
                // writer.flush();
            }

        } catch (SocketTimeoutException e) {
            LOG.warning("%s | Timeout | [%s] ".formatted(socketName, e.getMessage()));
            // send reponse that server is disconnecting
        } catch (IOException e) {
            LOG.warning("%s | IOException | [%s] ".formatted(socketName, e.getMessage()));
        } catch (Exception e) {
            LOG.warning("%s | Exception | %n[%s] ".formatted(socketName, e.getMessage()));
        } finally {
            LOG.info("%s Disconnected".formatted(socketName, clientSocket));
        }
    }

    /**
     *
     *
     * <pre>
     *
     * first line = request method + " " + requestTarget
     * headerName = "[a-Z][a-Z_-]*"
     * headerValue = "[a-Z][a-Z_-]*"
     * header = headerName + : + headerValue
     * headers = header + CRLF + header
     *
     * request = headers + CRLF + CRLF + body
     * </pre>
     *
     * @param startLine should have two paramenters: {@link HttpMethod} and {@link
     *     HttpRequest#target}
     * @param reader to read other data from. Will NEVER be closed.
     * @return parced request
     * @throws IOException if there are an issue with reading the {@code reader}
     * @throws IllegalArgumentException if there no two parameters separated by a space in the
     *     {@code startLine}
     * @throws IllegalArgumentException if the {@code requestTarget} doesn't exist
     */
    private static HttpRequest parseRequest(String startLine, BufferedReader reader)
            throws IOException {
        requireNonNull(startLine, "The startLine cannot be null");
        requireNonNull(reader, "The reader cannot be null");

        var splitStartLine = startLine.split(" ");

        if (splitStartLine.length != 2) {
            throw new IllegalArgumentException(
                    "Didn't find two space separated parameters".formatted(startLine));
        }

        var httpMethod =
                HttpMethod.valueOf(
                        splitStartLine[0].toUpperCase()); // throws exception if it isn't a correct method
        var requestTarget = validateRequestTarget(splitStartLine[1]);

        var headersOrRequestBody = readHeadersAndBody(reader);

        // headers and body are separated by "CRLFCRLF"
        var headersLength = -1;
        var bodyStartIndex = -1;

        var crlfMatchArray = new boolean[4];
        for (int i = 0; i < headersOrRequestBody.length; i++) {

            if (headersOrRequestBody[i] == 13) { // is "CR"
                // [!CR, !LF, !CR, !LF] || [CR, LF, !CR, !LF]
                if (!crlfMatchArray[0] || (crlfMatchArray[1] && !crlfMatchArray[2])) {
                    crlfMatchArray[crlfMatchArray[1] ? 2 : 0] = true;
                }
            } else if (headersOrRequestBody[i] == 10) { // is "LF"
                // [CR, !LF, !CR, !LF] || [CR, LF, CR, !LF]
                if (crlfMatchArray[1] || crlfMatchArray[3]) {

                    if (crlfMatchArray[3]) { // last LF, mark headers, body and exit
                        headersLength = i - 3;
                        if (i + 1 < headersOrRequestBody.length) {
                            bodyStartIndex = i + 1;
                        }
                        break;
                    } else {
                        crlfMatchArray[1] = true; // first LF
                    }
                }
            } else {
                crlfMatchArray = new boolean[4];
            }
        }

        assert headersLength >= -1;
        assert bodyStartIndex >= -1;
        assert headersLength <= headersOrRequestBody.length;
        assert bodyStartIndex < headersOrRequestBody.length;

        Map<String, String> headers =
                headersLength == -1
                        ? Collections.emptyMap()
                        : convertHeaders(Arrays.copyOf(headersOrRequestBody, headersLength));
        var body =
                bodyStartIndex == -1
                        ? new char[0]
                        : Arrays.copyOfRange(
                                headersOrRequestBody, bodyStartIndex, headersOrRequestBody.length);

        if (headers.isEmpty() && body.length == 0) {
            return new HttpRequest(httpMethod, requestTarget);
        } else if (headers.isEmpty()) {
            return new HttpRequest(httpMethod, requestTarget, body);
        } else if (body.length == 0) {
            return new HttpRequest(httpMethod, requestTarget, headers);
        } else {
            return new HttpRequest(httpMethod, requestTarget, headers, body);
        }
    }

    /**
     * Reads the data from {@code reader}. The {@code reader} will NEVER be closed after leaving the
     * method.
     *
     * @param reader to read data from
     * @return read data from {@code reader} into {@code char[]} array
     * @throws IOException if there issues with reading from {@code reader}
     */
    private static char[] readHeadersAndBody(BufferedReader reader) throws IOException {
        requireNonNull(reader);

        var buff = new char[1024];
        var buffList = new LinkedList<char[]>();
        var totalSize = 0;

        while (reader.ready()) {
            var length = reader.read(buff);
            if (length == -1) {
                break;
            }

            buffList.add(Arrays.copyOf(buff, length));
            totalSize = Math.addExact(totalSize, length);
        }

        var data = new char[totalSize];
        var dataWritePositionIndex = 0;

        for (char[] b : buffList) {
            System.arraycopy(b, 0, data, dataWritePositionIndex, b.length);

            dataWritePositionIndex = Math.addExact(dataWritePositionIndex, b.length);
        }

        assert totalSize == dataWritePositionIndex - 1
                : "The positon of last write isn't at the end of the array";

        return data;
    }

    /**
     *
     *
     * <pre>
     * headerName = "[a-Z][a-Z_-]*"
     * headerValue = "[a-Z][a-Z_-]*"
     * header = headerName + : + headerValue
     * headers = header + CRLF + header
     * </pre>
     *
     * <p>NO trailing CRLF or any characters after a header
     *
     * @param data to parse the headers from
     * @return converted {@code data}, if {@code data.length == 0}, an empty map is returned
     * @throws NullPointerException if {@code data} is null
     * @throws IllegalArgumentException if there are less than 3 character for a header
     * @throws IllegalArgumentException if there are 1 or 2 characters after a header that aren't equal to "CRLF"
     * @throws IllegalArgumentException if there is a trailing CRLF
     * @throws IllegalArgumentException if the first letter of a headerName isn't "[a-Z]"
     * @throws IllegalArgumentException if the other letter of a headerName isn't "[a-Z-_]"
     * @throws IllegalArgumentException if the first letter of a headerValue isn't "[a-Z]"
     * @throws IllegalArgumentException if the other letter of a headerValue isn't "[a-Z-_]"
     * @throws IllegalArgumentException if the other letter of a headerValue isn't "[a-Z-_]"
     * @throws IllegalArgumentException if the end of the header data stream was reached but the
     *     header wasn't finished completely
     * @throws IllegalArgumentException if the parsed header was already present
     */
    private static Map<String, String> convertHeaders(char[] data) {
        requireNonNull(data);

        var headers = new HashMap<String, String>();

        for (int i = 0; i < data.length; i += 2) {
            if (data.length - i < 3) {
                throw new IllegalArgumentException("There are less than 3 characters for a header");
            }

            i = convertHeaderLine(data, i, headers);

            if (i == data.length) {
                break;
            }

            if (data.length - i <= 1) {
                throw new IllegalArgumentException(
                        "There are only 1 character after a header: '%s'".formatted(data[i]));
            }

            // not CRLF
            if (data[i] != 10 && data[i + 1] != 13) {
                throw new IllegalArgumentException(
                        "There is an incorrect character after a header: '%s'".formatted(data[i]));
            }

            if(i + 1 == data.length) {
                throw new IllegalArgumentException("There is a trailing CRLF");
            }
        }
        return headers;
    }

    /**
     * Reads header data from the {@code data} at {@code startIndex} and untill "CR" or the end of
     * the {@code data} and then adds the header to the {@code headers}
     *
     * <pre>
     * headerName = "[a-Z][a-Z_-]*"
     * headerValue = "[a-Z][a-Z_-]*"
     * header = headerName + : + headerValue
     * </pre>
     *
     * @param data to read header data from
     * @param startIndex to start reading {@code data} at, should be non negative and {@code
     *     data.length - startIndex >= 3}
     * @param headers to store a header
     * @return the last index stopped at plus + 1. It is guaranteed that the returned value will be
     *     >= starIndex + 3
     * @throws IllegalArgumentException if {@code startIndex < 0}
     * @throws IllegalArgumentException if {@code data.length - startIndex < 3} }
     * @throws IllegalArgumentException if the first letter of the headerName isn't "[a-Z]"
     * @throws IllegalArgumentException if the other letter of the headerName isn't "[a-Z-_]"
     * @throws IllegalArgumentException if the first letter of the headerValue isn't "[a-Z]"
     * @throws IllegalArgumentException if the other letter of the headerValue isn't "[a-Z-_]"
     * @throws IllegalArgumentException if the other letter of the headerValue isn't "[a-Z-_]"
     * @throws IllegalArgumentException if the end of the {@code data} was reached but the header
     *     wasn't finished completely
     * @throws IllegalArgumentException if the parsed header was already present in the {@code
     *     headers}
     */
    private static int convertHeaderLine(
            char[] data, int startIndex, HashMap<String, String> headers) {
        requireNonNull(data);
        requireNonNull(headers);

        if (startIndex < 0) {
            throw new IllegalArgumentException("start index is negative: %s".formatted(startIndex));
        }

        if (data.length - startIndex < 3) {
            throw new IllegalArgumentException(
                    "The remaining amount of data to read is smaller than 3. i = %s, data.length = %s"
                            .formatted(startIndex, data.length));
        }

        int stopIndex = startIndex;

        // 1. Search for the start of a header name
        // 2. Parse the header name and search for its end
        // 3. Search for the start of a header value
        // 4. Parse the header value and search for its end
        var headerNameStartIndex = -1;
        var headerNameEndIndex = -1;
        var headerValueStartIndex = -1;
        var headerValueEndIndex = -1;
        for (int j = startIndex; j < data.length; j++, stopIndex = j) {
            var ch = data[startIndex];

            // 1. Search for header's name start character
            if (headerNameStartIndex == -1) {
                if (!Character.isLetter(ch)) {
                    throw new IllegalArgumentException(
                            "Incorrect character: '%s' in a first letter of the header name."
                                    + " Allowed 'a-zA-Z'");
                }

                headerNameStartIndex = startIndex;
                continue;
            }

            assert headerNameStartIndex != -1;

            // 2. Parse the header name and search for its end
            if (headerNameEndIndex == -1) {
                assert headerNameStartIndex < startIndex;

                // reached the end of the header name;
                if (ch == ':') {
                    headerNameEndIndex = startIndex - 1;
                    continue;
                }

                if (!Character.isLetter(ch) && ch != '-' && ch != '_') {
                    throw new IllegalArgumentException(
                            "Incorrect character: '%s' in a header name. Allowed 'a-zA-Z-_'"
                                    .formatted(ch));
                }

                continue;
            }

            assert headerNameEndIndex != -1;

            // 3. Search for the start of a header value
            if (headerValueStartIndex == -1) {
                if (!Character.isLetter(ch)) {
                    throw new IllegalArgumentException(
                            "Incorrect character: '%s' in a first letter of the header value."
                                    + " Allowed 'a-zA-Z'");
                }

                headerValueStartIndex = startIndex;
                continue;
            }

            assert headerValueStartIndex != -1;

            // 4. Parse the header value and search for its end
            if (headerValueEndIndex == -1) {
                assert headerValueStartIndex < startIndex;

                // reached the end of the header value;
                if (ch == 13) { // it's CR
                    headerNameEndIndex = startIndex - 1;
                    break;
                } else if (startIndex == data.length - 1) { // it's the end of the data array
                    headerNameEndIndex = startIndex;
                    break; // we are exiting the loop anyway but it is more readable
                }

                if (!Character.isLetter(ch) && ch != '-' && ch != '_') {
                    throw new IllegalArgumentException(
                            "Incorrect character: '%s' in a header value. Allowed 'a-zA-Z-_'"
                                    .formatted(ch));
                }

                continue;
            }
        }

        assert headerNameStartIndex != -1;

        if (headerNameEndIndex == -1 || headerValueStartIndex == -1 || headerValueEndIndex == -1) {
            throw new IllegalArgumentException(
                    "Reached the end of header data stream but couldn't finish the header.");
        }

        assert headerNameEndIndex - headerNameStartIndex >= 0;
        assert headerValueEndIndex - headerValueEndIndex >= 0;
        assert headerValueStartIndex - headerNameEndIndex == 2;

        var headerName =
                String.valueOf(
                        data, headerNameStartIndex, headerNameEndIndex - headerNameStartIndex + 1);
        var headerValue =
                String.valueOf(
                        data,
                        headerValueStartIndex,
                        headerValueEndIndex - headerValueStartIndex + 1);

        if (headers.containsKey(headerName)) {
            throw new IllegalArgumentException(
                    "Header: %s already is present".formatted(headerName));
        }

        headers.put(headerName, headerValue);

        ++stopIndex;
        assert stopIndex - startIndex >= 3;
        assert startIndex <= data.length;

        return startIndex;
    }

    /**
     * @param requestTarget to check if exists in the set of {@link Server#requestTargets}
     * @return passed {@code requestTarget}
     * @throws NullPointerException if the {@code requestTarget} is {@code null}
     * @throws IllegalArgumentException if the {@code requestTarget} doesn't exist
     */
    private static String validateRequestTarget(String requestTarget) {
        requireNonNull(requestTarget, "The requestTarget cannot be null");
        if (!requestTargets.contains(requestTarget)) {
            throw new IllegalArgumentException(
                    "The requestTarget doesn't exist: %s".formatted(requestTarget));
        }

        return requestTarget;
    }

    public static void main(String[] args) throws InterruptedException {
        var s = new Server();
        s.start();
        s.waitTillStop();
    }

    /**
     * TODO performance? Copies message in the constructor and every time it is requested @Immutable
     */
    private static class ChatMessage {

        private final char[] message;

        private final User user;

        public ChatMessage(char[] message, User user) {
            this.message = message.clone();
            this.user = user;
        }

        public char[] getMessage() {
            return message.clone();
        }

        public User getUser() {
            return user;
        }

        @Override
        public String toString() {
            return "ChatMessage [message=" + Arrays.toString(message) + ", user=" + user + "]";
        }
    }

    /**
     * @Immutable
     */
    private static class User {

        private final String username;

        private final String password;

        public User(String username, String password) {
            this.username = requireNonNull(username);
            this.password = requireNonNull(password);
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }
    }

    /**
     * @Immutable
     */
    public static class HttpRequest {
        private final HttpMethod method;
        private final String target;

        private final Optional<Map<String, String>> headers;
        private final Optional<char[]> body;

        public HttpRequest(HttpMethod method, String target) {
            this.method = requireNonNull(method);
            this.target = requireNonNull(target);
            this.headers = Optional.empty();
            this.body = Optional.empty();
        }

        /**
         * @param method
         * @param target
         * @param body of the request. Cannot have length zero
         */
        public HttpRequest(HttpMethod method, String target, char[] body) {
            this.method = requireNonNull(method);
            this.target = requireNonNull(target);
            this.headers = Optional.empty();

            if (body.length == 0) {
                throw new IllegalArgumentException("The length of the body cannot be zero");
            }
            this.body = Optional.of(requireNonNull(body));
        }

        public HttpRequest(HttpMethod method, String target, Map<String, String> headers) {
            this.method = requireNonNull(method);
            this.target = requireNonNull(target);
            this.body = Optional.empty();
            this.headers = Optional.of(new HashMap<>(requireNonNull(headers)));
        }

        /**
         * @param method
         * @param target
         * @param headers
         * @param body of the request. Cannot have length zero.
         */
        public HttpRequest(
                HttpMethod method, String target, Map<String, String> headers, char[] body) {
            this.method = requireNonNull(method);
            this.target = requireNonNull(target);
            this.headers = Optional.of(new HashMap<>(requireNonNull(headers)));

            if (body.length == 0) {
                throw new IllegalArgumentException("The length of the body cannot be zero");
            }
            this.body = Optional.of(requireNonNull(body));
        }

        public HttpMethod getMethod() {
            return method;
        }

        public String getTarget() {
            return target;
        }

        public Optional<Map<String, String>> getHeaders() {
            return Optional.of(new HashMap<>(headers.get()));
        }

        public Optional<char[]> getBody() {
            return Optional.of(body.get().clone());
        }

        public static enum HttpMethod {
            GET,
            POST;
        }

        @Override
        public String toString() {
            return "HttpRequest [method="
                    + method
                    + ", target="
                    + target
                    + ", headers="
                    + headers
                    + ", body="
                    + body
                    + "]";
        }
    }
}
