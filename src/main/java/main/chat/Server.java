package main.chat;

import static java.util.Objects.requireNonNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Serializable;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import main.chat.Server.HttpRequest.HttpMethod;
import main.chat.Server.HttpResponse.HttpStatus;

public class Server implements AutoCloseable {
    // Logging
    private static final Logger LOG = Logger.getLogger(Server.class.getName());

    {
        LOG.setLevel(Level.FINEST);
        Logger.getLogger("").getHandlers()[0].setLevel(Level.FINEST);
    }

    private static final DateTimeFormatter dateFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneOffset.UTC);

    // Socket Management
    private final int CLIENT_QUEUE_SIZE = 1;
    private final int CLIENT_POOL_SIZE = 1;
    private final int SERVICE_POOL_SIZE = 2;

    // TODO access pools only via an intrinsic lock
    private ThreadPoolExecutor clientPool = createPool(CLIENT_POOL_SIZE, CLIENT_QUEUE_SIZE);
    private ThreadPoolExecutor servicePool = createPool(SERVICE_POOL_SIZE, SERVICE_POOL_SIZE);

    private boolean isStarted;
    private boolean isClosed;
    private Optional<Thread> portListener = Optional.empty();
    private Optional<Thread> dataWriter = Optional.empty();

    // db
    private final BlockingQueue<ChatMessage> chatMessagesProcessor = new LinkedBlockingQueue<>();
    private final BlockingQueue<HttpRequest> httpRequestsProcessor = new LinkedBlockingQueue<>();

    /** Not thread safe, access ONLY via its intrinsic look using synchronized */
    private final LinkedList<ChatMessage> chatMessagesDatabase = new LinkedList<>();

    private final LinkedBlockingQueue<HttpRequest> httpRequestsDatabase =
            new LinkedBlockingQueue<>();
    private final BlockingQueue<User> chatUsers = new LinkedBlockingQueue<>();

    // HTTP management
    private static final Set<String> requestTargets = Set.of("/messages");

    private static ThreadPoolExecutor createPool(int threadPoolSize, int queueSize) {
        return new ThreadPoolExecutor(
                threadPoolSize,
                threadPoolSize,
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueSize)) {

            @Override
            protected void afterExecute(Runnable r, Throwable t) {
                var f = (FutureTask<?>) r;
                try {
                    f.get();
                    // handling escaped exceptions (i.e assertions) for debugging purposes
                } catch (ExecutionException e) {
                    e.getCause().printStackTrace();
                } catch (Exception e) {

                }
            }
        };
    }

    public Server() {
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    try {
                                        this.close();
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                }));
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
            servicePool.submit(
                    getHttpRequestProcessor(httpRequestsProcessor, httpRequestsDatabase));
            servicePool.submit(
                    getChatMessageProcessor(chatMessagesProcessor, chatMessagesDatabase));

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
    public synchronized void close() throws InterruptedException {
        if (!isStarted || isClosed) {
            return;
        }

        isClosed = true;

        System.out.println("Clean Up: " + this);
        var isInterrupted = cleanUpPortListener();
        isInterrupted = cleanUpConnectionPool() ? true : isInterrupted;
        isInterrupted = cleanUpServicePool() ? true : isInterrupted;
        cleanUpQeueus();
        System.out.println("Cleaned Up: " + this);

        notifyAll();
        if (isInterrupted) {
            throw new InterruptedException(
                    "During cleanup, interruption occured. The clean up was complete anyway");
        }
    }

    private synchronized boolean cleanUpPortListener() {
        if (portListener.isEmpty()) {
            throw new IllegalStateException("The Port Listener is not present");
        }

        Thread thread = portListener.get();
        thread.interrupt();
        portListener = Optional.empty();

        System.out.println("Stopping Port Listener Thread: " + thread);

        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            LOG.warning("Port Listener cleanup was interrupted");
            return true;
        }

        if (thread.getState() != Thread.State.TERMINATED) {
            System.out.println(
                    "Couldn't shutdown on hook" + " thread: " + thread + " " + thread.getState());
        } else {
            System.out.println("Stoped Port Listener Thread: " + thread);
        }

        return false;
    }

    private synchronized boolean cleanUpConnectionPool() {
        System.out.println("Closing Connection Pool: " + clientPool);

        try {
            var notStartedClients = clientPool.shutdownNow();

            // running the tasks that haven been started with an interrupt status,
            // so all the opened sockets are closed
            Thread.currentThread().interrupt();
            notStartedClients.forEach(Runnable::run);
            Thread.interrupted();

            if (!clientPool.awaitTermination(1, TimeUnit.SECONDS)) {
                LOG.warning("Couldn't shutdown Client Pool. Timeout");
            }

            System.out.println("Closed Connection Pool");
        } catch (InterruptedException e) {
            LOG.warning(
                    "Couldn't wait till shutdown termination of the Client Pool. Got interrupted");
            return true;
        }

        return false;
    }

    private synchronized boolean cleanUpServicePool() {
        System.out.println("Closing Service Pool: " + servicePool);
        try {
            assert servicePool.shutdownNow().isEmpty();

            if (!servicePool.awaitTermination(10, TimeUnit.SECONDS)) {
                LOG.warning("Couldn't shutdown the Service Pool. Timeout");
            }
            System.out.println("Closed Service Pool");
        } catch (InterruptedException e) {
            LOG.warning(
                    "Couldn't wait till shutdown termination of the Service Pool. Got interrupted");
            return true;
        }

        return false;
    }

    private void cleanUpQeueus() {
        System.out.println("Cleaning up queues");
        chatMessagesProcessor.clear();
        httpRequestsProcessor.clear();
        chatMessagesDatabase.clear();
        httpRequestsDatabase.clear();
        System.out.println("Queues have been cleaned up");
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

    private static Runnable getHttpRequestProcessor(
            BlockingQueue<HttpRequest> httpRequests, BlockingQueue<HttpRequest> dbHttpRequests) {
        requireNonNull(httpRequests);
        requireNonNull(dbHttpRequests);

        var requestFile = "./requests.log";
        return () -> {
            try (var requestWriter = new BufferedWriter(new FileWriter(requestFile))) {

                while (!Thread.currentThread().isInterrupted()) {
                    var httpRequest = httpRequests.take();
                    dbHttpRequests.add(httpRequest);

                    var body = httpRequest.getBody();
                    var headers = httpRequest.getHeaders();

                    var msg =
                            "[%s] | %.5s | %s | %s%n"
                                    .formatted(
                                            httpRequest.getSocketName(),
                                            httpRequest.getMethod(),
                                            headers.isPresent() ? headers.get() : "[]",
                                            body.isPresent() ? Arrays.toString(body.get()) : "[]");

                    requestWriter.write(msg);
                    requestWriter.flush();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warning("%s was interrupted".formatted(Thread.currentThread().getName()));
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Exception was thrown", e);
            }
        };
    }

    private static Runnable getChatMessageProcessor(
            BlockingQueue<ChatMessage> chatMessages, LinkedList<ChatMessage> dbChatMessages) {
        requireNonNull(chatMessages);
        requireNonNull(dbChatMessages);

        var chatMessagesFile = "./chat.log";
        return () -> {
            try (var chatWriter =
                    new BufferedWriter(
                            new OutputStreamWriter(new FileOutputStream(chatMessagesFile)))) {

                while (!Thread.currentThread().isInterrupted()) {
                    var chatMessage = chatMessages.take();

                    synchronized (dbChatMessages) {
                        dbChatMessages.add(chatMessage);
                    }

                    var username = chatMessage.getUser().getUsername();
                    var header =
                            "%s | %s:> "
                                    .formatted(
                                            username,
                                            dateFormatter.format(chatMessage.getCreatedDate()));
                    var indent = " ".repeat(header.length());
                    var data =
                            String.valueOf(chatMessage.getMessage())
                                    .replaceAll("\n", System.lineSeparator() + indent);

                    var message = "%s%s%n".formatted(header, data);

                    System.out.print(message);
                    chatWriter.write(message);
                    chatWriter.flush();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warning("%s was interrupted".formatted(Thread.currentThread().getName()));
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Exception was thrown", e);
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

                Optional<HttpResponse> optResponse = Optional.empty();
                try {
                    LOG.info("Received Request: from '%s'".formatted(clientSocket));
                    var httpRequest = parseRequest(socketName, firstLine, reader);

                    LOG.info("Parsed Request: '%s' from '%s'".formatted(httpRequest, clientSocket));

                    optResponse = Optional.of(handleHttpRequest(httpRequest));
                } catch (IllegalArgumentException e) {
                    // cleaning socket, if we have an issue with parsing the data
                    var skipped = reader.skip(Integer.MAX_VALUE);

                    LOG.log(
                            Level.WARNING,
                            "Exception processing request. Skipped characters: " + skipped,
                            e);
                    optResponse = Optional.of(new HttpResponse(HttpStatus.BAD, e.getMessage()));
                } finally {
                    if (optResponse.isEmpty()) {
                        optResponse = Optional.of(new HttpResponse(HttpStatus.BAD, "Error"));
                    }

                    LOG.info(
                            "Sending Response: '%s' to '%s'"
                                    .formatted(optResponse.get(), clientSocket));
                    sendResponse(clientSocket, optResponse.get());
                }
            }

        } catch (SocketTimeoutException e) {
            LOG.log(Level.WARNING, "%s | Timeout".formatted(socketName), e);
            // send reponse that server is disconnecting
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "%s | IOException".formatted(socketName), e);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "%s | Exception".formatted(socketName), e);
        } finally {
            LOG.info("%s Disconnected".formatted(socketName, clientSocket));
        }
    }

    private HttpResponse handleHttpRequest(HttpRequest httpRequest) {
        requireNonNull(httpRequest);
        httpRequestsProcessor.add(httpRequest);

        var body = httpRequest.getBody();
        return switch (httpRequest.getMethod()) {
            case POST -> {
                if (body.isPresent()) {
                    char[] data = body.get();
                    var message = new ChatMessage(data, new User(httpRequest.getSocketName(), ""));
                    chatMessagesProcessor.add(message);
                } else {
                    throw new IllegalArgumentException(
                            "POST request to '%s' should have a body"
                                    .formatted(httpRequest.getTarget()));
                }

                yield new HttpResponse(HttpStatus.OK, "Success");
            }
            case GET -> {
                var lastId = getLastIdParam(httpRequest);
                // starting at this id we will return messages
                // for now -1 would mean we need all of them
                lastId++;

                var messages = new LinkedList<ChatMessage>();

                if(lastId >= chatMessagesDatabase.size()) {
                    yield new HttpResponse(HttpStatus.OK, messages);
                }

                synchronized (chatMessagesDatabase) {

                    var listIter = chatMessagesDatabase.listIterator(lastId);

                    while (listIter.hasNext()) {
                        var chMessage = listIter.next();
                        messages.add(chMessage);
                    }
                }

                yield new HttpResponse(HttpStatus.OK, messages);
            }
        };
    }

    /**
     * Retrieve an 's' parameter and parases it, returning it as a size;
     *
     * <p>If the parameter is not present {@code -1} is returned
     *
     * <p>If the parameter is not a digit, or has more digits than 3, it will be silently ignored
     * and {@code -1} returned
     *
     * @param request to retrieve the 's' parameter from
     * @return value under the 's' parameter that is more or equal to 1 and not bigger than 1000. if
     *     the parameter is not present {@code -1} is returned.
     * @thorws {@link IllegalArgumentException} if parameter is less than 1 or biggern than 1000
     */
    private int getLastIdParam(HttpRequest request) {
        requireNonNull(request);

        var lastId = -1;
        var optParams = request.getParameters();
        if (optParams.isPresent()) {
            var sizeString = optParams.get().get("lastId");
            if (sizeString != null && sizeString.matches("\\d{1,3}")) {
                lastId = Integer.parseInt(sizeString);
                if (lastId < 0 || lastId > 1000) {
                    throw new IllegalArgumentException(
                            "Parameter: 'lastId' cannot be smaller than 0 and bigger than 1000");
                }
            }
        }

        assert lastId >= -1 && lastId <= 1000;
        return lastId;
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
     * @param requestLine should have two paramenters: {@link HttpMethod} and {@link
     *     HttpRequest#target}
     * @param reader to read other data from. Will NEVER be closed.
     * @return parced request
     * @throws IOException if there are an issue with reading the {@code reader}
     * @throws IllegalArgumentException if there no two parameters separated by a space in the
     *     {@code startLine}
     * @throws IllegalArgumentException if the {@code requestTarget} doesn't exist
     */
    private static HttpRequest parseRequest(
            String socketName, String requestLine, BufferedReader reader) throws IOException {
        requireNonNull(socketName, "Socket cannot be null");
        requireNonNull(requestLine, "The startLine cannot be null");
        requireNonNull(reader, "The reader cannot be null");

        var splitStartLine = requestLine.split("[ ]", 2);

        if (splitStartLine.length != 2) {
            throw new IllegalArgumentException(
                    "Didn't find two space separated parameters. Http Method and Http Target"
                            .formatted(requestLine));
        }

        var headersOrRequestBody = readData(reader);
        LOG.finest(
                """
                Received Data: Socket: %s
                '%s
                %s'\
                """
                        .formatted(
                                socketName,
                                requestLine,
                                headersOrRequestBody.length != 0
                                        ? Arrays.toString(headersOrRequestBody)
                                        : ""));
        // throws exception if it isn't a correct method
        var httpMethod = HttpMethod.valueOf(splitStartLine[0].toUpperCase());
        var requestTargetAndParameters = splitStartLine[1].split("\\?", 2);
        var requestTarget = validateRequestTarget(requestTargetAndParameters[0]);
        var parameters =
                requestTargetAndParameters.length > 1
                        ? Optional.of(parseParameters(requestTargetAndParameters[1]))
                        : Optional.<Map<String, String>>empty();

        var headerAndBody = getHeadersOrBody(headersOrRequestBody);

        var httpRequestBuilder = new HttpRequest.Builder(socketName, httpMethod, requestTarget);

        if (headerAndBody.v1.isPresent()) {
            httpRequestBuilder.headers(headerAndBody.v1.get());
        } else if (headerAndBody.v2.isPresent()) {
            httpRequestBuilder.body(headerAndBody.v2.get());
        } else if (parameters.isPresent()) {
            httpRequestBuilder.parameters(parameters.get());
        }

        return httpRequestBuilder.build();
    }

    public static Tuple<Optional<Map<String, String>>, Optional<char[]>> getHeadersOrBody(
            char[] data) {
        requireNonNull(data);

        // headers and body are separated by "CRLFCRLF"
        var headersLength = -1;
        var bodyStartIndex = -1;

        var crlfMatch = new boolean[4];
        for (int i = 0; i < data.length; i++) {

            if (data[i] == 13) { // is "CR"
                // [!CR, !LF, !CR, !LF] || [CR, LF, !CR, !LF]
                if (!crlfMatch[0] || (crlfMatch[1] && !crlfMatch[2])) {
                    assert !crlfMatch[0] && !crlfMatch[1] && !crlfMatch[2] && !crlfMatch[3]
                            || (crlfMatch[0] && crlfMatch[1] && !crlfMatch[2] && !crlfMatch[3]);

                    crlfMatch[crlfMatch[1] ? 2 : 0] = true;
                } else {
                    crlfMatch = new boolean[4];
                }
            } else if (data[i] == 10) { // is "LF"
                // [CR, !LF, !CR, !LF] || [CR, LF, CR, !LF]
                if ((crlfMatch[0] && !crlfMatch[1]) || crlfMatch[2]) {
                    assert (crlfMatch[0] && !crlfMatch[1] && !crlfMatch[2] && !crlfMatch[3])
                            || (crlfMatch[0] && crlfMatch[1] && crlfMatch[2] && !crlfMatch[3]);

                    if (crlfMatch[2]) { // last LF, mark headers, body
                        headersLength = i - 3 == 0 ? -1 : i - 3;

                        if (i + 1 < data.length) {
                            bodyStartIndex = i + 1;
                        }

                        break;
                    } else {
                        crlfMatch[1] = true; // first LF
                    }
                } else {
                    crlfMatch = new boolean[4];
                }
            } else {
                crlfMatch = new boolean[4];
            }
        }

        assert headersLength == -1 || headersLength > 0;
        assert bodyStartIndex >= -1;
        assert headersLength <= data.length;
        assert bodyStartIndex < data.length;

        var headers =
                headersLength != -1 ? convertHeaders(Arrays.copyOf(data, headersLength)) : null;
        var body =
                bodyStartIndex != -1 ? Arrays.copyOfRange(data, bodyStartIndex, data.length) : null;

        return new Tuple<>(Optional.ofNullable(headers), Optional.ofNullable(body));
    }

    public static class Tuple<V1, V2> {
        public final V1 v1;
        public final V2 v2;

        public Tuple(V1 v1, V2 v2) {
            this.v1 = requireNonNull(v1);
            this.v2 = requireNonNull(v2);
        }
    }

    /**
     * Parameters should have the followwing pattern [a-Z]+=[a-Z0-9]+&? Dublicated params will be
     * silently ignored
     *
     * @param parameters
     * @return
     */
    private static Map<String, String> parseParameters(String parameters) {
        requireNonNull(parameters);

        if (parameters.length() < 3) {
            throw new IllegalArgumentException(
                    "Length of parameters should be more than 2. Params: '%s'"
                            .formatted(parameters));
        }

        var result = new HashMap<String, String>();
        var params = parameters.toCharArray();

        var isKey = true; // are we parsing a key of a parameter
        var keyStartIndex = -1; // inclusive
        var keyStopIndex = -1; // exclusive

        var valueStartIndex = -1; // inclusive
        var valueStopIndex = -1; // exclusive
        for (int i = 0; i < params.length; i++) {
            var ch = params[i];

            if (isKey) {
                assert valueStartIndex == -1;
                assert valueStopIndex == -1;
                assert keyStopIndex == -1;

                if (!Character.isLetter(ch) && ch != '=') {
                    throw new IllegalArgumentException(
                            "Paremeter's key contains not a letter: letter='%s'".formatted(ch));
                }

                if (keyStartIndex == -1) {
                    if (ch == '=') {
                        throw new IllegalArgumentException(
                                "Parameter's key starts with '=' letter");
                    }

                    keyStartIndex = i;
                } else if (ch == '=') {
                    isKey = false;
                    keyStopIndex = i;
                }
                continue;
            } else {
                assert keyStartIndex != -1;
                assert keyStopIndex != -1;
                assert valueStopIndex == -1;

                if (!Character.isLetter(ch) && !Character.isDigit(ch) && ch != '&') {
                    throw new IllegalArgumentException(
                            "Paremeter's value contains not a letter or digit: letter='%s'"
                                    .formatted(ch));
                }

                if (valueStartIndex == -1) {
                    if (ch == '&') {
                        throw new IllegalArgumentException(
                                "Parameter's value starts with '&' letter");
                    }
                    valueStartIndex = i;
                }

                if (ch == '&') {
                    isKey = true;
                    valueStopIndex = i;
                } else if (i == params.length - 1) {
                    valueStopIndex = i + 1;
                } else {
                    continue;
                }
            }

            assert keyStartIndex >= 0;
            assert keyStopIndex >= 1;
            assert keyStartIndex < params.length - 2;
            assert keyStopIndex < params.length - 1;
            assert valueStartIndex >= 1;
            assert valueStopIndex >= 2;
            assert valueStartIndex < params.length;
            assert valueStopIndex <= params.length;

            assert keyStopIndex - keyStartIndex >= 1;
            assert valueStopIndex - valueStartIndex >= 1;

            var key = String.valueOf(params, keyStartIndex, keyStopIndex - keyStartIndex);
            var value = String.valueOf(params, valueStartIndex, valueStopIndex - valueStartIndex);

            keyStartIndex = -1;
            keyStopIndex = -1;
            valueStartIndex = -1;
            valueStopIndex = -1;

            result.putIfAbsent(key, value); // ignoring dublicated params
        }

        assert valueStopIndex == -1;

        if (keyStartIndex != -1 || keyStopIndex != -1 || valueStartIndex != -1) {
            throw new IllegalArgumentException(
                    "Couldn't finish a parameter. Params: '%s'".formatted(Arrays.toString(params)));
        }

        return result;
    }

    /**
     * Reads the data from {@code reader}. The {@code reader} will NEVER be closed after leaving the
     * method.
     *
     * @param reader to read data from
     * @return read data from {@code reader} into {@code char[]} array
     * @throws IOException if there issues with reading from {@code reader}
     */
    private static char[] readData(BufferedReader reader) throws IOException {
        requireNonNull(reader);

        var buff = new char[1024];
        var buffList = new LinkedList<char[]>();
        buffList.add(new char[] {'\r', '\n'});
        var totalSize = 2;

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

        assert totalSize == dataWritePositionIndex
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
     * @throws IllegalArgumentException if there are 1 or 2 characters after a header that aren't
     *     equal to "CRLF"
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

            i = convertHeader(data, i, headers);

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

            if (i + 1 == data.length) {
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
    private static int convertHeader(char[] data, int startIndex, HashMap<String, String> headers) {
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
                                            .formatted(ch)
                                    + " Allowed 'a-zA-Z'");
                }

                headerNameStartIndex = startIndex;
                continue;
            }

            assert headerNameStartIndex != -1;

            // 2. Parse the header name and search for its end
            if (headerNameEndIndex == -1) {
                assert headerNameStartIndex < j;

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
                                    + " Allowed 'a-zA-Z'".formatted(ch));
                }

                headerValueStartIndex = startIndex;
                continue;
            }

            assert headerValueStartIndex != -1;

            // 4. Parse the header value and search for its end
            if (headerValueEndIndex == -1) {
                assert headerValueStartIndex < j;

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
                    "The requestTarget doesn't exist: '%s'".formatted(requestTarget));
        }

        return requestTarget;
    }

    private void sendResponse(Socket socket, HttpResponse httpResponse) throws IOException {
        requireNonNull(socket);
        requireNonNull(httpResponse);

        try {
            var socketOut = new ObjectOutputStream(socket.getOutputStream());
            socketOut.writeInt(httpResponse.getStatus().statusCode);
            socketOut.writeObject(httpResponse.getBody());
            socketOut.flush();
        } catch (IOException e) {
            throw new IOException("Couldn't send a response: %s".formatted(httpResponse), e);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        try (var s = new Server()) {
            s.start();
            s.waitTillStop();
        }
    }

    /**
     * TODO performance? Copies message in the constructor and every time it is requested @Immutable
     */
    public static class ChatMessage implements Serializable {

        private final char[] message;
        private final User user;
        private final Instant created = Instant.now();

        /** equal to the number of instances of the class created */
        private final int id;

        private static int idCounter;

        public ChatMessage(char[] message, User user) {
            this.id = idCounter++;
            this.message = requireNonNull(message.clone());
            this.user = requireNonNull(user);
        }

        public int getId() {
            return id;
        }

        public char[] getMessage() {
            return message.clone();
        }

        public User getUser() {
            return user;
        }

        public Instant getCreatedDate() {
            return created;
        }

        @Override
        public String toString() {
            return "ChatMessage [message=" + Arrays.toString(message) + ", user=" + user + "]";
        }
    }

    /**
     * @Immutable
     */
    public static class User implements Serializable {

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
        private final String socketName;

        private final HttpMethod method;
        private final String target;

        private final Optional<Map<String, String>> headers;
        private final Optional<Map<String, String>> parameters;

        private final Optional<char[]> body;

        private HttpRequest(
                String socketName,
                HttpMethod method,
                String target,
                Map<String, String> headers,
                Map<String, String> parameters,
                char[] body) {

            this.socketName = requireNonNull(socketName);
            this.method = requireNonNull(method);
            this.target = requireNonNull(target);

            this.headers =
                    headers == null
                            ? Optional.empty()
                            : Optional.of(new HashMap<>(requireNonNull(headers)));
            this.parameters =
                    parameters == null
                            ? Optional.empty()
                            : Optional.of(new HashMap<>(requireNonNull(parameters)));

            if (body != null) {
                if (body.length == 0) {
                    throw new IllegalArgumentException("The length of the body cannot be zero");
                }
                this.body = Optional.of(body.clone());
            } else {
                this.body = Optional.empty();
            }
        }

        public static class Builder {
            private final String socketName;
            private final HttpMethod method;
            private final String target;
            private Optional<Map<String, String>> parameters = Optional.empty();
            private Optional<Map<String, String>> headers = Optional.empty();
            private Optional<char[]> body = Optional.empty();

            public Builder(String socketName, HttpMethod method, String target) {
                this.socketName = requireNonNull(socketName);
                this.method = requireNonNull(method);
                this.target = requireNonNull(target);
            }

            /**
             * @param headers to add to {@link HttpRequest}
             * @return this {@link Builder} to continue building the {@link HttpRequest}
             * @throws NullPointerException if {@code headers} is {@code null} or its keys or values
             */
            public Builder headers(Map<String, String> headers) {
                requireNonNull(headers);
                var hasNulls =
                        headers.entrySet().stream()
                                .anyMatch(
                                        e -> {
                                            return e.getKey() == null || e.getValue() == null;
                                        });
                if (hasNulls) {
                    throw new NullPointerException(
                            "The headers cannot contain null keys or values");
                }
                this.headers = Optional.of(headers);
                return this;
            }

            /**
             * @param parameters to add to {@link HttpRequest}
             * @return this {@link Builder} to continue building the {@link HttpRequest}
             * @throws NullPointerException if {@code parameters} is {@code null} or its keys or
             *     values
             */
            public Builder parameters(Map<String, String> parameters) {
                requireNonNull(parameters);
                var hasNulls =
                        parameters.entrySet().stream()
                                .anyMatch(
                                        e -> {
                                            return e.getKey() == null || e.getValue() == null;
                                        });
                if (hasNulls) {
                    throw new NullPointerException(
                            "The parameters cannot contain null keys or values");
                }

                this.parameters = Optional.of(parameters);
                return this;
            }

            public Builder body(char[] body) {
                requireNonNull(body);

                this.body = Optional.of(body);
                return this;
            }

            public HttpRequest build() {
                var body = this.body.isPresent() ? this.body.get() : null;
                var headers = this.headers.isPresent() ? this.headers.get() : null;
                var parameters = this.parameters.isPresent() ? this.parameters.get() : null;

                return new HttpRequest(socketName, method, target, headers, parameters, body);
            }
        }

        public String getSocketName() {
            return socketName;
        }

        public HttpMethod getMethod() {
            return method;
        }

        public String getTarget() {
            return target;
        }

        public Optional<Map<String, String>> getHeaders() {
            return headers.isPresent() ? Optional.of(new HashMap<>(headers.get())) : headers;
        }

        public Optional<Map<String, String>> getParameters() {
            return parameters.isPresent()
                    ? Optional.of(new HashMap<>(parameters.get()))
                    : parameters;
        }

        public Optional<char[]> getBody() {
            return body.isPresent() ? Optional.of(body.get().clone()) : body;
        }

        public static enum HttpMethod {
            GET,
            POST;
        }

        @Override
        public String toString() {
            return "HttpRequest [socketName="
                    + socketName
                    + ", method="
                    + method
                    + ", target="
                    + target
                    + ", headers="
                    + (headers.isPresent() ? headers.get() : headers)
                    + ", parameters="
                    + (parameters.isPresent() ? parameters.get() : parameters)
                    + ", body="
                    + (body.isPresent() ? Arrays.toString(body.get()) : body)
                    + "]";
        }
    }

    /**
     * @Immutable
     */
    public static class HttpResponse {

        private final HttpStatus status;
        private final Serializable body;

        public HttpResponse(HttpStatus status, String message) {
            this.status = requireNonNull(status);
            this.body = requireNonNull(message);
        }

        public HttpResponse(HttpStatus status, Serializable body) {
            this.status = requireNonNull(status);
            this.body = requireNonNull(body);
        }

        public static enum HttpStatus {
            OK(100),
            BAD(500);

            public final int statusCode;

            private HttpStatus(int statusCode) {
                this.statusCode = statusCode;
            }

            public static Optional<HttpStatus> valueOf(int status) {
                for (var code : values()) {
                    if (code.statusCode == status) {
                        return Optional.of(code);
                    }
                }

                return Optional.empty();
            }
        }

        public HttpStatus getStatus() {
            return status;
        }

        public Serializable getBody() {
            return body;
        }

        @Override
        public String toString() {
            return "HttpResponse [status="
                    + status
                    + ", body="
                    + Arrays.toString(String.valueOf(body).toCharArray())
                    + "]";
        }
    }
}
