package main.chat;

import main.chat.Client.ServerConnector.ServerMessage;
import main.chat.Client.UIManager.UIMessage;

import static java.util.Objects.requireNonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.Socket;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

import javax.swing.undo.UndoManager;

public class Client {
    private static final Logger LOG = Logger.getLogger(Client.class.getName());

    private final BlockingQueue<UIMessage> uiMessageQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<ServerMessage> serverMessageQueue =
            new LinkedBlockingQueue<>();

    private final UIManager ui = new UIManager(serverMessageQueue);
    private final ServerConnector serverConnector =
            new ServerConnector("127.0.0.1", 8800, serverMessageQueue, uiMessageQueue);

    public static void main(String[] args) {
        new Client().start();
    }

    private void start() {
        var uiManager = new Thread(ui);
        var tServerConnector = new Thread(serverConnector);

        uiManager.start();
        tServerConnector.start();
        try {
            uiManager.join();
            tServerConnector.join();
        } catch (InterruptedException e) {

        }
    }

    public static class UIManager implements Runnable {
        private final BlockingQueue<ServerMessage> toServerMessageQueue;


        public UIManager(BlockingQueue<ServerMessage> toServerMessageQueue) {
            this.toServerMessageQueue = Objects.requireNonNull(toServerMessageQueue);
        }

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
            var reader = new BufferedReader(new InputStreamReader(System.in));
            try {
                System.out.print(":> ");
                while (!Thread.currentThread().isInterrupted()) {
                    if (!reader.ready()) {
                        Thread.sleep(2_000);
                        continue;
                    }

                    List<String> input = readInput(reader);
                    if (input.isEmpty()) {
                        System.out.printf("%n:> ");
                        continue;
                    }
                    var uiMsg = new ServerMessage(input);
                    LOG.info(uiMsg.toString());

                    // print("\033[2K\033[A".repeat(uiMsg.getText().size()));
                    toServerMessageQueue.put(uiMsg);
                    System.out.printf("%n:> ");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        /**
         * Reading input until not a 'Line Feed' is met in the end. The input is split by line
         * separator.
         *
         * @param reader to read the input from
         * @return A list of strings that represents input text split by line separator
         * @throws IOException if there is an issue with reading input from the {@code reader}
         */
        private List<String> readInput(BufferedReader reader) throws IOException {
            Objects.requireNonNull(reader);

            var result = new LinkedList<String>();

            // TODO initialize once
            var charBuff = new char[100];
            // TODO if new line separator equals "\n\r" and it is split between two char
            // buffs we are cooked
            var length = 0;
            while ((length = reader.read(charBuff)) != -1) {
                var lines = String.valueOf(charBuff, 0, length).split(System.lineSeparator());
                for (var line : lines) {
                    result.add(line);
                }

                // if last char is not line feed then stop reading
                if (charBuff[length - 1] != 10) {
                    break;
                }
            }

            assert !reader.ready();
            return result;
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
            private final List<String> text;

            public ServerMessage(List<String> text) {
                this.text = Collections.unmodifiableList(Objects.requireNonNull(text));
            }

            public List<String> getLines() {
                return text;
            }

            @Override
            public String toString() {
                return text.toString();
            }
        }

        @Override
        public void run() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    var msg = toServerMessageQueue.take();

                    var sb = new StringBuilder();
                    // TODO weird change
                    msg.getLines().forEach(t -> sb.append(t).append("\n"));
                    sb.deleteCharAt(sb.length() - 1);

                    LOG.info(sb.toString());

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

                        post(writer, sb.toString());

                        String disconnectionMessage = "[%s:%s] | Disconnected"
                                .formatted(
                                        serverSocket.getInetAddress(),
                                        serverSocket.getPort());
                        LOG.info( disconnectionMessage);

                        toUIMessageQueue.put(new UIMessage(connectionMessage));
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

        private void post(PrintStream writer, String data) {
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
