package main.chat;

import static java.util.Objects.requireNonNull;

import main.chat.Client.InputManager.InputMassage;
import main.chat.Client.InputManager.InputMassageResponse;
import main.chat.Client.ServerConnector.ServerMessage;
import main.chat.Client.UIManager.UIMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Client {
    private static final Logger LOG = Logger.getLogger(Client.class.getName());

    private final BlockingQueue<UIMessage> toUIMessageQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<ServerMessage> toServerMessageQueue = new LinkedBlockingQueue<>();

    private BlockingQueue<InputMassage> inputToUImanager = new LinkedBlockingQueue<>();
    private BlockingQueue<InputMassageResponse> uiManagerToInput = new LinkedBlockingQueue<>();

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

        private BlockingQueue<InputMassage> toUIManager;
        private BlockingQueue<InputMassageResponse> toInputManager;

        public InputManager(
                BlockingQueue<InputMassage> toUIManager,
                BlockingQueue<InputMassageResponse> toInputManager) {
            this.toUIManager = requireNonNull(toUIManager);
            this.toInputManager = requireNonNull(toInputManager);
        }

        public static class InputMassage {
            private char[] buff;

            public char[] getBuff() {
                return buff;
            }

            public InputMassage(char[] buff) {
                this.buff = buff;
            }
        }

        public static class InputMassageResponse {

            public InputMassageResponse() {}
        }

        @Override
        public void run() {
            try {
                LOG.log(Level.FINEST, "Waiting for a suart request from UIManager ");
                var startRequest = toInputManager.take();
                LOG.log(Level.FINEST, "Received a start request '{}' from UIManager", startRequest);

                var reader = new InputStreamReader(System.in);
                while (!Thread.currentThread().isInterrupted()) {

                    var input = readInput(reader);
                    if (input.length == 0) {
                        continue;
                    }

                    var inputMassage = new InputMassage(input);

                    LOG.log(Level.FINEST, "Sending {} to UIManager", inputMassage);
                    toUIManager.put(inputMassage);

                    LOG.log(Level.FINEST, "Waiting a response from UIManager to {} ", inputMassage);
                    var response = toInputManager.take();
                    LOG.log(
                            Level.FINEST,
                            "Received a response '{}' from UIManager to '{}' ",
                            new Object[] {response, inputMassage});
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
        private BlockingQueue<InputMassage> inputToUImanager;
        private BlockingQueue<InputMassageResponse> toInputManagerQueue;

        public UIManager(
                BlockingQueue<ServerMessage> toServerMessageQueue,
                BlockingQueue<UIMessage> toUIManager,
                BlockingQueue<InputMassage> inputToUImanager,
                BlockingQueue<InputMassageResponse> uiManagerToInput) {

            this.serverMessageQueue = requireNonNull(toServerMessageQueue);
            this.toUIManagerQueue = requireNonNull(toUIManager);
            this.inputToUImanager = requireNonNull(inputToUImanager);
            this.toInputManagerQueue = requireNonNull(uiManagerToInput);
        }

        public static class UIInputRequest {}

        /** Immutable class */
        public static class UIMessage {
            private final List<String> lines;

            public UIMessage(List<String> lines) {
                if (lines.isEmpty()) {
                    throw new IllegalArgumentException("The text cannot be empty");
                }
                this.lines = Collections.unmodifiableList(Objects.requireNonNull(lines));
            }

            public UIMessage(String message) {
                Objects.requireNonNull(message, "The message cannot be null");

                this.lines = List.of(message);
            }

            public List<String> getLines() {
                return lines;
            }

            @Override
            public String toString() {
                return lines.toString();
            }
        }

        @Override
        public void run() {
            try {

                var startResponse = new InputMassageResponse();
                LOG.log(
                        Level.FINEST,
                        "Sending: '{}' to InputManager as starting request",
                        startResponse);
                toInputManagerQueue.put(startResponse);

                System.out.printf("%n:> ");
                while (!Thread.currentThread().isInterrupted()) {

                    var inputMassage = inputToUImanager.poll(1, TimeUnit.SECONDS);
                    if (inputMassage != null) {
                        LOG.log(Level.FINEST, "Received :'{}' from InputManager", inputMassage);

                        var serverMessage = new ServerMessage(inputMassage.getBuff());

                        LOG.log(
                                Level.FINEST,
                                "Sending: '{}', to {}",
                                new Object[] {serverMessage, ServerConnector.class});
                        serverMessageQueue.put(serverMessage);

                        System.out.printf("%n:");

                        var blocker = blockInput();
                        Thread.sleep(10000);
                        blocker.cancel(true);

                        System.out.print("> ");

                        var inputMResponse = new InputMassageResponse();
                        LOG.log(Level.FINEST, "Sending: '{}' to InputManager", inputMResponse);
                        toInputManagerQueue.put(inputMResponse);
                        //
                        // LOG.log(Level.FINEST, "Waiting: {} to respond to: '{}'", new
                        // Object[]{ServerConnector.class});
                        //
                        // LOG.log(Level.FINEST, "Waiting: {} to respond to: '{}'", new
                        // Object[]{ServerConnector.class});

                    }

                    //
                    // List<String> input = readInput(reader);
                    // if (input.isEmpty()) {
                    //     System.out.printf("%n:> ");
                    //     continue;
                    // }
                    //
                    // var uiMsg = new ServerMessage(input);
                    // LOG.info(uiMsg.toString());
                    //
                    // // print("\033[2K\033[A".repeat(uiMsg.getText().size()));
                    // toServerMessageQueue.put(uiMsg);
                    //
                    // var blocker = blockInput();
                    // Thread.sleep(5000);
                    // // var responce = toUIManagerQueue.take();
                    // blocker.cancel(true);
                    //
                    // System.out.printf("%n:> ");
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                e.printStackTrace();
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
                return data.toString();
            }
        }

        @Override
        public void run() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    var msg = toServerMessageQueue.take();

                    try (var serverSocket = new Socket(ipAddress, port);
                            var reader =
                                    new BufferedReader(
                                            new InputStreamReader(serverSocket.getInputStream()));
                            var writer = new PrintStream(serverSocket.getOutputStream())) {

                        String connectionMessage =
                                "[%s:%s] | Connected | %s"
                                        .formatted(
                                                serverSocket.getInetAddress(),
                                                serverSocket.getPort(),
                                                serverSocket.toString());
                        LOG.info(connectionMessage);

                        post(writer, msg.getData());

                        String disconnectionMessage =
                                "[%s:%s] | Disconnected"
                                        .formatted(
                                                serverSocket.getInetAddress(),
                                                serverSocket.getPort());
                        LOG.info(disconnectionMessage);

                        // toUIMessageQueue.put(new UIMessage(connectionMessage));
                    } catch (Exception e) {
                        LOG.info(
                                "ServerSocket:[ip:%s | port:%s] | Exception | %s"
                                        .formatted(ipAddress, port, e.getMessage()));
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

        private void post(PrintStream writer, char[] data) {
            requireNonNull(writer);
            requireNonNull(data);

            var sb = new StringBuilder();
            sb.append("post /messages\r\n");
            sb.append("\r\n\r\n");
            sb.append(data);

            writer.print(sb.toString());
        }
    }
}
