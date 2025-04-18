package main.chat;

import static java.util.Objects.requireNonNull;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.OptionalDataException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StreamCorruptedException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import main.chat.Client.HttpRequest.HttpMethod;
import main.chat.Client.HttpResponse.HttpStatus;
import main.chat.Client.InputManager.InputMessage;
import main.chat.Client.InputManager.InputMessageResponse;
import main.chat.Client.ServerConnector.ServerMessage;
import main.chat.Client.UIManager.UIMessage;

import main.chat.Server.ChatMessage;

public class Client {
    private static final Logger LOG = Logger.getLogger(Client.class.getName());

    static {
        LOG.setLevel(Level.FINEST);
        // soooo weird
        Logger.getLogger("").getHandlers()[0].setLevel(Level.FINEST);
    }

    private static final DateTimeFormatter dateFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                    .withZone(ZoneId.systemDefault());

    private final BlockingQueue<UIMessage> toUIMessageQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<ServerMessage> toServerMessageQueue = new LinkedBlockingQueue<>();

    private BlockingQueue<InputMessage> inputToUImanager = new LinkedBlockingQueue<>();
    private BlockingQueue<InputMessageResponse> uiManagerToInput = new LinkedBlockingQueue<>();

    private final InputManager inputManager = new InputManager(inputToUImanager, uiManagerToInput);
    private final UIManager ui =
            new UIManager(
                    toServerMessageQueue, toUIMessageQueue, inputToUImanager, uiManagerToInput);
    private final ServerConnector serverConnector =
            new ServerConnector("127.0.0.1", 8800, toServerMessageQueue, toUIMessageQueue);

    public static void main(String[] args) {
        new Client().start();
    }

    private void start() {
        var uiManager = new Thread(ui);
        var tServerConnector = new Thread(serverConnector);
        var tInputManager = new Thread(inputManager);

        uiManager.start();
        tServerConnector.start();
        tInputManager.start();
        try {
            uiManager.join();
            tServerConnector.join();
            tInputManager.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static class InputManager implements Runnable {

        private BlockingQueue<InputMessage> toUIManager;
        private BlockingQueue<InputMessageResponse> toInputManager;

        public InputManager(
                BlockingQueue<InputMessage> toUIManager,
                BlockingQueue<InputMessageResponse> toInputManager) {
            this.toUIManager = requireNonNull(toUIManager);
            this.toInputManager = requireNonNull(toInputManager);
        }

        public static class InputMessage {
            private char[] buff;

            public char[] getBuff() {
                return buff;
            }

            public InputMessage(char[] buff) {
                this.buff = buff;
            }

            @Override
            public String toString() {
                return "InputMessage []";
            }
        }

        public static class InputMessageResponse {

            @Override
            public String toString() {
                return "InputMessageResponse []";
            }
        }

        @Override
        public void run() {
            try {
                LOG.log(
                        Level.FINEST,
                        "Waiting for a start request from '%s'".formatted(UIManager.class));
                var startRequest = toInputManager.take();
                LOG.log(
                        Level.FINEST,
                        "Received a start request '%s' from '%s'"
                                .formatted(startRequest, UIManager.class));

                var reader = new InputStreamReader(System.in);
                while (!Thread.currentThread().isInterrupted()) {

                    var input = readInput(reader);
                    if (input.length == 0) {
                        continue;
                    }

                    var inputMassage = new InputMessage(input);

                    LOG.log(
                            Level.FINEST,
                            "Sending '%s' to '%s'".formatted(inputMassage, UIManager.class));
                    toUIManager.put(inputMassage);

                    LOG.log(
                            Level.FINEST,
                            "Waiting a response from UIManager to '%s'".formatted(inputMassage));
                    var response = toInputManager.take();
                    LOG.log(
                            Level.FINEST,
                            "Received a response '%s' from '%s'"
                                    .formatted(response, UIManager.class));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warning("%s was interrupted".formatted(Thread.currentThread()));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        /**
         * Reading input until not a 'Line Feed' is met in the end.
         *
         * @param reader to read the input from
         * @return An array of characters that represents input text, if there is no input an array
         *     with {@code length 0} is returned
         * @throws IOException if there is an issue with reading input from the {@code reader}
         * @throws InterruptedException if interruption occurs
         */
        private static char[] readInput(InputStreamReader reader)
                throws IOException, InterruptedException {
            requireNonNull(reader);

            var buffs = new LinkedList<char[]>();

            // TODO initialize once
            var charBuff = new char[1024];
            // TODO if new line separator equals "\n\r" and it is split between two char
            // buffs we are cooked

            var totalLength = 0;
            while (!Thread.currentThread().isInterrupted()) {
                if (!reader.ready()) {
                    Thread.sleep(2_000);
                    continue;
                }

                var length = reader.read(charBuff);
                if (length == -1) {
                    break;
                }

                totalLength = Math.addExact(totalLength, length);
                buffs.add(length == charBuff.length ? charBuff : Arrays.copyOf(charBuff, length));

                // if last char is not line feed then stop reading
                if (charBuff[length - 1] != 10) {
                    break;
                }

                charBuff = new char[1024];
            }

            var input = new char[totalLength];
            var nextInputWriteIndex = 0;
            for (var buff : buffs) {
                System.arraycopy(buff, 0, input, nextInputWriteIndex, buff.length);
                nextInputWriteIndex = Math.addExact(nextInputWriteIndex, buff.length);
            }

            assert nextInputWriteIndex == totalLength;

            return input;
        }
    }

    public static class UIManager implements Runnable {
        private final BlockingQueue<ServerMessage> serverMessageQueue;
        private final BlockingQueue<UIMessage> toUIManagerQueue;
        private BlockingQueue<InputMessage> inputToUImanager;
        private BlockingQueue<InputMessageResponse> toInputManagerQueue;

        public UIManager(
                BlockingQueue<ServerMessage> toServerMessageQueue,
                BlockingQueue<UIMessage> toUIManager,
                BlockingQueue<InputMessage> inputToUImanager,
                BlockingQueue<InputMessageResponse> uiManagerToInput) {

            this.serverMessageQueue = requireNonNull(toServerMessageQueue);
            this.toUIManagerQueue = requireNonNull(toUIManager);
            this.inputToUImanager = requireNonNull(inputToUImanager);
            this.toInputManagerQueue = requireNonNull(uiManagerToInput);
        }

        public static class UIInputRequest {}

        /** Immutable class */
        public static class UIMessage {
            private final Optional<HttpResponse<String>> chatPostResponse;
            private final Optional<HttpResponse<List<ChatMessage>>> chatMessagesResponse;

            private UIMessage(
                    HttpResponse<String> httpResponse,
                    HttpResponse<List<ChatMessage>> chatMessages) {
                if (httpResponse != null && chatMessages != null) {
                    throw new IllegalAccessError("Only one argument can be non null");
                }

                if (httpResponse == null && chatMessages == null) {
                    throw new IllegalAccessError("Both arguments cannot be null");
                }

                this.chatPostResponse = Optional.ofNullable(httpResponse);
                this.chatMessagesResponse = Optional.ofNullable(chatMessages);
            }

            public static UIMessage createPostChatMessageResponse(
                    HttpResponse<String> chatMessage) {
                return new UIMessage(requireNonNull(chatMessage), null);
            }

            public static UIMessage createGetChatMessagesResponse(
                    HttpResponse<List<ChatMessage>> chatMessages) {
                return new UIMessage(null, requireNonNull(chatMessages));
            }

            public Optional<HttpResponse<String>> getChatPostResponse() {
                return chatPostResponse;
            }

            public Optional<HttpResponse<List<ChatMessage>>> getChatMessagesResponse() {
                return chatMessagesResponse;
            }

            @Override
            public String toString() {
                return "UIMessage [chatPostResponse="
                        + chatPostResponse
                        + ", chatMessagesResponse="
                        + chatMessagesResponse
                        + "]";
            }
        }

        @Override
        public void run() {
            try {

                var startResponse = new InputMessageResponse();
                LOG.log(
                        Level.FINEST,
                        "Sending: '%s' to InputManager as starting request"
                                .formatted(startResponse));
                toInputManagerQueue.put(startResponse);

                System.out.printf("%n:> ");
                while (!Thread.currentThread().isInterrupted()) {

                    var inputMessage = inputToUImanager.poll(1, TimeUnit.SECONDS);
                    if (inputMessage != null) {
                        sendServerConnectorMessage(inputMessage);
                        System.out.printf(" %n:> ");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.log(Level.WARNING, "An interruption occurred", e);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Unexpected Exception occurred", e);
            }
        }

        /**
         * Puts a {@link ServerMessage} with {@code inputMassage} data to the {@link
         * UIManager#serverMessageQueue} Waits indefinitely for {@link
         * UIManager#serverMessageQueue}'s response Sends an {@link InputMessageResponse} to {@link
         * InputManager} after the {@link ServerConnector} responded
         *
         * @param inputMessage to send to the server
         * @throws InterruptedException if waits or posting of message is interrupted
         */
        private void sendServerConnectorMessage(InputMessage inputMessage)
                throws InterruptedException {
            requireNonNull(inputMessage);

            LOG.log(Level.FINEST, "Received :'%s' from InputManager".formatted(inputMessage));

            formatInputMessage(inputMessage.getBuff());

            var serverMessage = new ServerMessage(inputMessage.getBuff());
            LOG.log(
                    Level.FINEST,
                    "Sending: '%s', to '%s'".formatted(serverMessage, ServerConnector.class));
            serverMessageQueue.put(serverMessage);

            LOG.log(Level.FINEST, "Waiting Response: from '%s'".formatted(ServerConnector.class));

            var block = blockInput();
            try {
                var response = toUIManagerQueue.poll(5, TimeUnit.SECONDS);
                validateServerConnectorResponse(serverMessage, response);
            } catch (IllegalArgumentException e) {
                block.cancel(true);
                System.out.printf(" %n:< Couldn't send the message: %s".formatted(e.getMessage()));
            } finally {
                block.cancel(true);
            }

            var inputMResponse = new InputMessageResponse();
            LOG.log(
                    Level.FINEST,
                    "Sending Message: '%s' to '%s'".formatted(inputMResponse, InputManager.class));
            toInputManagerQueue.put(inputMResponse);
        }

        /**
         * Validates Server's response to posting a chat message.
         *
         * @param serverMessage for logging purposes. Non null
         * @param response to handle. Can be null
         * @throws IllegalArgumentException if something is wrong with the response, via {@link
         *     IllegalArgumentException#getMessage()} the details can be extracted
         */
        private static void validateServerConnectorResponse(
                ServerMessage serverMessage, UIMessage response) {
            requireNonNull(serverMessage);

            if (response == null) {
                LOG.log(
                        Level.SEVERE,
                        "Received NO Response: from '%s' to the request '%s'"
                                .formatted(ServerConnector.class, serverMessage));

                throw new IllegalArgumentException("Response Timed Out");
            }

            LOG.log(
                    Level.FINEST,
                    "Received Response: '%s' from '%s'".formatted(response, ServerConnector.class));

            if (response.getChatPostResponse().isEmpty()) {
                LOG.log(
                        Level.SEVERE,
                        "Inncorrect response to the post Message request: Request: %s, Response"
                                .formatted(serverMessage, response));

                throw new IllegalArgumentException("Received Incorrect Response");
            }

            var chatPostResponse = response.getChatPostResponse().get();

            if (chatPostResponse.getStatus() != HttpStatus.OK) {
                LOG.severe(
                        "Received Bad Status of the Request. Message: %s Response: %s"
                                .formatted(serverMessage, chatPostResponse));

                throw new IllegalArgumentException(
                        chatPostResponse.getMessage().orElse("Bad Request Status"));
            }
        }

        /**
         * Formats inputed text, adding time, and indent
         *
         * @param buff format based on this input
         */
        private void formatInputMessage(char[] buff) {
            requireNonNull(buff);

            var header = "[%s]> ".formatted(dateFormatter.format(LocalDateTime.now()));
            var indent = " ".repeat(header.length());
            var formattedInput = new StringBuilder(header);

            var lineBreaks = 0;
            for (char c : buff) {
                formattedInput.append(c);

                if (c == 10) {
                    lineBreaks = Math.incrementExact(lineBreaks);
                    formattedInput.append(indent);
                }
            }

            if (lineBreaks > 0) {
                System.out.printf("\033[%sA", lineBreaks);
            }
            System.out.print("\r");
            System.out.print(formattedInput.toString());
        }

        /**
         * Creates and starts a {@link Thread} that prints a loading characters until the {@link
         * Thread} is canceled via the {@link Future}. The cursor position shifts to the next
         * character and remains there all the time.
         *
         * <p>How to cancel: The cancellation can be done via {@link Future#cancel(boolean)} sending
         * {@code true} to interrupt blocking; otherwise the delay might be up to {@code 500
         * milliseconds}
         *
         * <p>When it is checked for cancellation: Task checks for a interruption every iteration,
         * each iteration is blocked by interruptable call to a {@link Thread#sleep(long)} method.
         *
         * <p>When the task will be canceled: The response to a cancellation is immediate.
         *
         * <p>What actions will be done in response to cancellation: The cursor position will be
         * left on the same location (i.e.) next character to the position when the task was
         * started.
         *
         * @return a {@link Future} of the started thread
         */
        private Future<Void> blockInput() {
            final var load = new char[] {'|', '/', '-', '\\'};

            var future =
                    new FutureTask<Void>(
                            () -> {
                                var out = new OutputStreamWriter(System.out);
                                int i = 0;
                                try {
                                    while (!Thread.currentThread().isInterrupted()) {

                                        out.write(load[i++]);
                                        out.write("\033[D");
                                        out.flush();

                                        if (i >= load.length) {
                                            i = 0;
                                        }

                                        Thread.sleep(500);
                                    }
                                    out.write("\033[J");
                                } catch (InterruptedException e) {
                                } catch (Exception e) {
                                    LOG.log(
                                            Level.SEVERE,
                                            "Encountered exception in blockInput()",
                                            e);
                                }
                            },
                            null);
            new Thread(future).start();

            return future;
        }

        private synchronized void print(List<String> msgs) {
            System.out.printf("%n\t<: %s%n", msgs.remove(0));
            for (String msg : msgs) {
                System.out.println("\t   " + msg);
            }
            System.out.print(":> ");
        }

        private synchronized void print(String msg) {
            System.out.println("%\t<: " + msg);
            System.out.print(":> ");
        }
    }

    public static class ServerConnector implements Runnable {

        private final String ipAddress;
        private final int port;
        private final BlockingQueue<ServerMessage> toServerMessageQueue;
        private final BlockingQueue<UIMessage> toUIMessageQueue;

        public ServerConnector(
                String ipAddress,
                int port,
                BlockingQueue<ServerMessage> toServerMessageQueue,
                BlockingQueue<UIMessage> toUiMessageQeueu) {

            this.ipAddress = Objects.requireNonNull(ipAddress);
            this.port = port;
            this.toServerMessageQueue = Objects.requireNonNull(toServerMessageQueue);
            this.toUIMessageQueue = Objects.requireNonNull(toUiMessageQeueu);
        }

        /** Immutable class */
        public static class ServerMessage {
            private final char[] data;

            public ServerMessage(char[] data) {
                this.data = requireNonNull(data).clone();
            }

            public char[] getData() {
                return data.clone();
            }

            @Override
            public String toString() {
                return "ServerMessage [data=" + Arrays.toString(data) + "]";
            }
        }

        @Override
        public void run() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    try (var serverSocket = new Socket(ipAddress, port)) {

                        var socketName =
                                "[%s:%s]"
                                        .formatted(
                                                serverSocket.getInetAddress(),
                                                serverSocket.getPort());
                        LOG.info("[%s] | Connected".formatted(socketName));

                        serverSocket.setSoTimeout(15_000);

                        while (!Thread.currentThread().isInterrupted() && !serverSocket.isClosed()) {
                            var msg = toServerMessageQueue.poll(2, TimeUnit.SECONDS);

                            if (msg != null) {
                                var uiMessage = UIMessage.createPostChatMessageResponse(postChatMessage(serverSocket, msg));

                                LOG.log(
                                        Level.FINEST,
                                        "Sending Message: '%s' to '%s'"
                                                .formatted(uiMessage, UIManager.class));
                                toUIMessageQueue.put(uiMessage);
                            }
                        }

                        LOG.info("[%s] | Disconnected".formatted(serverSocket));
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warning("%s was interrupted".formatted(Thread.currentThread()));
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Exception was thrown", e);
            }
        }

        /**
         * Sends the {@code msg} to the server and waits for the response
         *
         * @param serverSocket to establish the communication with the server
         * @param msg that will be send to the server
         * @return {@link HttpResponse} from the server to the message
         */
        private HttpResponse<String> postChatMessage(Socket serverSocket, ServerMessage msg) {
            requireNonNull(serverSocket);
            requireNonNull(msg);

            var socketName =
                    "%s:%s".formatted(serverSocket.getInetAddress(), serverSocket.getPort());

            var httpRequest =
                    new HttpRequest(socketName, HttpMethod.POST, "/messages", msg.getData());

            Optional<HttpResponse<String>> optResponse = Optional.empty();
            try {
                LOG.log(
                        Level.INFO,
                        "Sending Request: '%s' to socket '%s'".formatted(httpRequest, socketName));

                optResponse = Optional.of(sendRequest(serverSocket, httpRequest, String.class));
                LOG.log(
                        Level.INFO,
                        "Received Response: '%s' from socket '%s'"
                                .formatted(optResponse.get(), socketName));
            } catch (Exception e) {
                LOG.log(
                        Level.SEVERE,
                        "ServerSocket:[ip:%s | port:%s] | Exception".formatted(ipAddress, port),
                        e);

                optResponse = Optional.of(HttpResponse.createWithMessage(HttpStatus.BAD, e.getMessage()));
                try {
                    serverSocket.close();
                } catch (IOException e1) {
                    LOG.log(Level.SEVERE, "Couldn't close the socket on error", e);
                }
            }

            return optResponse.get();
        }

        private static <Body> HttpResponse<Body> sendRequest(Socket socket, HttpRequest httpRequest, Class<Body> bodyType)
                throws IOException {
            requireNonNull(socket);
            requireNonNull(httpRequest);
            requireNonNull(bodyType);

            LOG.finest("Sending Request: %s".formatted(httpRequest));
            var requestMsg = getRequestMsg(httpRequest);
            LOG.finest("Sending Message:%n'%s'".formatted(requestMsg));

            var writer = new PrintWriter(socket.getOutputStream());
            writer.print(requestMsg);
            writer.flush();

            ObjectInputStream in = null;
            try {
                in = new ObjectInputStream(socket.getInputStream());
            } catch (SocketTimeoutException e) {
                throw new RuntimeException("Server response timed out", e);
            } catch (IOException e) {
                throw new IOException("Failed receiving response from the server", e);
            }

            try {
                HttpResponse<Body> response = parseResponse(in, bodyType);
                LOG.finest("Received Response: %s".formatted(response));
                return response;
            } catch (IllegalArgumentException e) {
                LOG.log(Level.SEVERE, 
                        """
                        Failed to parse Server's ([%s]) response.
                        Response to te request: '%s'
                        Because: '%s'\
                        """ .formatted(socket, httpRequest, e.getMessage()), e);;

                throw new IllegalArgumentException("Couldn't parse Server's response");
            }
        }

        /**
         * Converts {@code httpRequest} to a request messag.e
         *
         *
         * httpMethod and target will always be present in the returned result
         * header key and value will be joined with a {@code :} and if there are
         * multiple of them separated by {@code CRLF}
         *
         * <pre>
         * httpMethod target\r\n
         * headerKey:headerValue\r\n
         * <\r\nbody>
         * </pre
         *
         * @param httpRequest
         * @return
         */
        private static String getRequestMsg(HttpRequest httpRequest) {
            requireNonNull(httpRequest);

            var headers = new StringBuilder();
            if (httpRequest.getHeaders().isPresent()) {
                httpRequest
                        .getHeaders()
                        .get()
                        .forEach(
                                (k, v) -> {
                                    headers.append(k).append(":").append(v).append("\r\n");
                                });
            }

            var body =
                    httpRequest.getBody().isPresent()
                            ? "\r\n\r\n" + String.valueOf(httpRequest.getBody().get())
                            : "";

            var requestMsg =
                    """
                    %s %s\
                    %s\
                    %s\
                    """
                            .formatted(
                                    httpRequest.getMethod(),
                                    httpRequest.getTarget(),
                                    headers,
                                    body);

            return requestMsg;
        }

        /**
         * Reads and Parses into http response. First should be an int primitive that can be
         * converted to {@link HttpStatus#} then object of type {@code Body}"
         *
         * @param in to read data from
         * @param bodyClass 
         * @return {@link HttpResponse} parsed response
         * @throws IOException if there are issues reading {@code in}
         * @throws IllegalArgumentException if the input contains incorrect format
         */
        private static <Body> HttpResponse<Body> parseResponse(ObjectInputStream in, Class<Body> bodyClass)
                throws IOException {
            requireNonNull(in);
            requireNonNull(bodyClass);

            try {
                int statusInt = in.readInt();
                var status = HttpStatus.valueOf(statusInt);
                if (status.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Server send an incorrect status value: '%s'".formatted(statusInt));
                }

                var body = bodyClass.cast(in.readObject());

                if (body instanceof String) {
                    return HttpResponse.createWithMessage(status.get(), (String) body);
                } else {
                    return HttpResponse.createWithBody(status.get(), body);
                }

            } catch (EOFException
                    | ClassNotFoundException
                    | InvalidClassException
                    | StreamCorruptedException
                    | OptionalDataException
                    | ClassCastException e) {
                throw new IllegalArgumentException(
                        "Server responded with incorrect data format", e);
            } catch (IOException e) {
                throw new IOException("Coldn't read server response", e);
            }
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
        private final Optional<char[]> body;

        public HttpRequest(String socketName, HttpMethod method, String target) {
            this.socketName = requireNonNull(socketName);
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
        public HttpRequest(String socketName, HttpMethod method, String target, char[] body) {
            this.socketName = requireNonNull(socketName);
            this.method = requireNonNull(method);
            this.target = requireNonNull(target);
            this.headers = Optional.empty();

            if (body.length == 0) {
                throw new IllegalArgumentException("The length of the body cannot be zero");
            }
            this.body = Optional.of(requireNonNull(body));
        }

        public HttpRequest(
                String socketName, HttpMethod method, String target, Map<String, String> headers) {
            this.socketName = requireNonNull(socketName);
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
                String socketName,
                HttpMethod method,
                String target,
                Map<String, String> headers,
                char[] body) {
            this.socketName = requireNonNull(socketName);
            this.method = requireNonNull(method);
            this.target = requireNonNull(target);
            this.headers = Optional.of(new HashMap<>(requireNonNull(headers)));

            if (body.length == 0) {
                throw new IllegalArgumentException("The length of the body cannot be zero");
            }
            this.body = Optional.of(requireNonNull(body));
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

        public Optional<char[]> getBody() {
            return body.isPresent() ? Optional.of(body.get().clone()) : body;
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
                    + (headers.isPresent() ? headers.get() : headers)
                    + ", body="
                    + (body.isPresent() ? Arrays.toString(body.get()) : body)
                    + "]";
        }
    }

    /**
     * @Immutable
     */
    public static class HttpResponse<Body> {

        private final HttpStatus status;
        private final Optional<String> message;
        private final Optional<Body> body;

        private HttpResponse(HttpStatus status, Body body, String infoMessage) {
            if (body != null && infoMessage != null) {
                throw new IllegalAccessError("Only one argument can be non null");
            }

            if (body == null && infoMessage == null) {
                throw new IllegalAccessError("Both arguments cannot be null");
            }

            this.status = requireNonNull(status);
            this.body = Optional.ofNullable(body);
            this.message = Optional.ofNullable(infoMessage);
        }

        public static <Body> HttpResponse<Body> createWithBody(HttpStatus status, Body body) {
            return new HttpResponse<>(status, requireNonNull(body), null);
        }

        public static <Body> HttpResponse<Body> createWithMessage(
                HttpStatus status, String infoMessage) {
            return new HttpResponse<>(status, null, requireNonNull(infoMessage));
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

        public Optional<String> getMessage() {
            return message;
        }

        public Optional<Body> getBody() {
            return body;
        }

        @Override
        public String toString() {
            return "HttpResponse [status=" + status + ", body=" + body + "]";
        }
    }
}
