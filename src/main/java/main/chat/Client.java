package main.chat;

import static java.util.Objects.requireNonNull;

import main.chat.Client.HttpRequest.HttpMethod;
import main.chat.Client.HttpResponse.HttpStatus;
import main.chat.Client.InputManager.InputMessage;
import main.chat.Client.InputManager.InputMessageResponse;
import main.chat.Client.ServerConnector.ServerMessage;
import main.chat.Client.UIManager.UIMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
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

public class Client {
    private static final Logger LOG = Logger.getLogger(Client.class.getName());

    static {
        // soooo weird
        LOG.setLevel(Level.FINEST);
        Logger.getLogger("").getHandlers()[0].setLevel(Level.FINEST);
    }

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
                return "InputMessage [buff=" + Arrays.toString(buff) + "]";
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
            private final HttpResponse httpResponse;

            public UIMessage(HttpResponse httpResponse) {
                this.httpResponse = requireNonNull(httpResponse);
            }

            public HttpResponse getHttpResponse() {
                return httpResponse;
            }

            @Override
            public String toString() {
                return "UIMessage [httpResponse=" + httpResponse + "]";
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

                    var inputMassage = inputToUImanager.poll(1, TimeUnit.SECONDS);
                    if (inputMassage != null) {
                        LOG.log(
                                Level.FINEST,
                                "Received :'%s' from InputManager".formatted(inputMassage));

                        var serverMessage = new ServerMessage(inputMassage.getBuff());

                        LOG.log(
                                Level.FINEST,
                                "Sending: '%s', to '%s'"
                                        .formatted(serverMessage, ServerConnector.class));
                        serverMessageQueue.put(serverMessage);

                        System.out.printf("%n:");
                        var blocker = blockInput();
                        var inputMResponse = new InputMessageResponse();

                        LOG.log(
                                Level.FINEST,
                                "Sending Message: '%s' to '%s'"
                                        .formatted(inputMResponse, InputManager.class));
                        toInputManagerQueue.put(inputMResponse);

                        LOG.log(
                                Level.FINEST,
                                "Waiting Response: from '%s'".formatted(ServerConnector.class));
                        var uiMessageResponse = toUIManagerQueue.take();
                        LOG.log(
                                Level.FINEST,
                                "Received Response: '%s' from '%s'"
                                        .formatted(uiMessageResponse, ServerConnector.class));

                        var httpResponse = uiMessageResponse.getHttpResponse();

                        blocker.cancel(true);
                        if (httpResponse.getStatus() == HttpStatus.OK) {
                            System.out.print("> ");

                        } else {
                            System.out.printf(
                                    "< Couldn't send a message due to '%s'%n:> ",
                                    httpResponse.getMessage());
                        }
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
                    var msg = toServerMessageQueue.take();

                    Optional<UIMessage> optUIMessage = Optional.empty();
                    try (var serverSocket = new Socket(ipAddress, port)) {
                        var socketName =
                                "%s:%s"
                                        .formatted(
                                                serverSocket.getInetAddress(),
                                                serverSocket.getPort());

                        LOG.info("[%s] | Connected".formatted(socketName));

                        var httpRequest =
                                new HttpRequest(
                                        socketName, HttpMethod.POST, "/messages", msg.getData());

                        LOG.log(
                                Level.INFO,
                                "Sending Request: '%s' to socket '%s'"
                                        .formatted(httpRequest, socketName));
                        var httpResponse = request(serverSocket, httpRequest);
                        LOG.log(
                                Level.INFO,
                                "Received Response: '%s' from socket '%s'"
                                        .formatted(httpResponse, socketName));

                        optUIMessage = Optional.of(new UIMessage(httpResponse));

                    } catch (Exception e) {
                        LOG.log(
                                Level.SEVERE,
                                "ServerSocket:[ip:%s | port:%s] | Exception"
                                        .formatted(ipAddress, port),
                                e);

                        optUIMessage =
                                Optional.of(
                                        new UIMessage(
                                                new HttpResponse(HttpStatus.BAD, e.getMessage())));
                    } finally {
                        var uiMessage =
                                optUIMessage.isPresent()
                                        ? optUIMessage.get()
                                        : new UIMessage(
                                                new HttpResponse(
                                                        HttpStatus.BAD, "Unexpected Error"));

                        LOG.log(
                                Level.FINEST,
                                "Sending Message: '%s' to '%s'"
                                        .formatted(uiMessage, UIManager.class));
                        toUIMessageQueue.put(uiMessage);

                        LOG.info("[%s] | Disconnected".formatted(""));
                    }

                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

            } catch (Exception e) {
            }
        }

        private static HttpResponse request(Socket socket, HttpRequest httpRequest)
                throws IOException {
            requireNonNull(socket);
            requireNonNull(httpRequest);

            LOG.log(Level.FINEST, "Sending Request: '%s'".formatted(httpRequest));

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

            LOG.finest("Request Data:%n\"%s\"".formatted(requestMsg));

            socket.setSoTimeout(10_000);

            String responseLine = null;
            try (var writer = new PrintWriter(socket.getOutputStream());
                    var reader =
                            new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                writer.print(requestMsg);
                writer.flush();
                responseLine = reader.readLine();
            } catch (SocketTimeoutException e) {
                throw new SocketTimeoutException("Server response timed out");
            } catch (IOException e) {
                throw new IOException("Failed communication with the server", e);
            }

            try {
                return parseHttpResponse(responseLine);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        """
                        Failed to parse Server's ([%s]) response.
                        Response line: '%s'
                        Because: '%s'
                        """
                                .formatted(socket, responseLine, e.getMessage()),
                        e);
            }
        }

        /**
         * Parses http response line that has a form of "\d{3} \s+"
         *
         * @param responseLine to parse
         * @return {@link HttpResponce} parsed
         * @throws IllegalArgumentException if {@code responseLine} is {@code null}
         * @throws IllegalArgumentException if {@code responseLine} cannot be split into two strings
         *     using ' ' character
         * @throws IllegalArgumentException if first part of split {@code responseLine} cannot be
         *     converted to {@link HttpStatus}
         */
        private static HttpResponse parseHttpResponse(String responseLine) {
            if (responseLine == null) {
                throw new IllegalArgumentException("Server returned 'EOF'");
            }

            var indexOfSpace = responseLine.indexOf(' ');
            if (indexOfSpace == -1) {
                throw new IllegalArgumentException(
                        "Server returned an incorrect response: '%s'".formatted(responseLine));
            }

            var message = responseLine.substring(indexOfSpace + 1);

            var stringStatusCode = responseLine.substring(0, indexOfSpace);
            if (!stringStatusCode.matches("\\d{3}")) {
                throw new IllegalArgumentException(
                        "Server returned incorrect value as a status code: '%s'"
                                .formatted(stringStatusCode));
            }

            var httpStatusCode = HttpStatus.valueOf(Integer.parseInt(stringStatusCode));
            if (httpStatusCode.isEmpty()) {
                throw new IllegalArgumentException(
                        "Server returned incorrect value as a status code: '%s'"
                                .formatted(stringStatusCode));
            }

            return new HttpResponse(httpStatusCode.get(), message);
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
    public static class HttpResponse {

        private final HttpStatus status;

        private final String message;

        public HttpResponse(HttpStatus status, String message) {
            this.status = status;
            this.message = message;
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

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return "HttpResponse [status=" + status + ", message=" + message + "]";
        }
    }
}
