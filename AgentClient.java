import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Backend client for the Agent GUI.
 *
 * Handles all socket communication with the Bank and AuctionHouse.
 *
 * The auction house connection is persistent: one socket stays open for the
 * full session so the AuctionHouse can push OUTBID and WINNER notifications
 * back to this client. Commands (LIST_ITEMS, BID) are sent through that same
 * socket and their responses are routed via a BlockingQueue back to the
 * calling thread.
 */
public class AgentClient {

    private final String bankHost;
    private final int bankPort;

    private int agentId;
    private int accountId;

    private volatile String houseHost;
    private volatile int housePort;

    // Persistent house connection — kept open to receive push notifications.
    private Socket houseSocket;
    private PrintWriter housePersistentOut;

    // Command responses from the house are placed here by the listener thread
    // and polled by whichever GUI thread sent the command.
    private final BlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();

    /**
     * Creates a new AgentClient connected to a known Bank location.
     *
     * @param bankHost bank host address
     * @param bankPort bank port number
     */
    public AgentClient(String bankHost, int bankPort) {
        this.bankHost = bankHost;
        this.bankPort = bankPort;
    }

    /**
     * Opens an agent account with the Bank.
     *
     * @param name           agent name
     * @param initialBalance starting balance
     * @return raw Bank response
     * @throws IOException if Bank communication fails
     */
    public String openAgent(String name, double initialBalance) throws IOException {
        String response = sendBankCommand("OPEN_AGENT " + name + " " + initialBalance);
        if (response.startsWith("OK AGENT")) {
            parseAgentRegistration(response);
        }
        return response;
    }

    /**
     * Gets this agent's balance from the Bank.
     *
     * @return balance response
     * @throws IOException if Bank communication fails
     */
    public String getBalance() throws IOException {
        return sendBankCommand("BALANCE " + accountId);
    }

    /**
     * Gets registered auction houses from the Bank.
     *
     * @return house list response
     * @throws IOException if Bank communication fails
     */
    public String listHouses() throws IOException {
        return sendBankCommand("LIST_HOUSES");
    }

    /**
     * Opens a persistent connection to an AuctionHouse and starts a background
     * listener thread. OUTBID and WINNER lines are forwarded to onNotification;
     * all other lines (command responses) go onto the responseQueue for
     * listItems() and placeBid() to collect.
     *
     * If a previous connection is open it is closed before the new one opens.
     *
     * @param host           auction house host
     * @param port           auction house port
     * @param onNotification callback invoked on the listener thread for each
     *                       OUTBID or WINNER push notification
     * @throws IOException if the connection cannot be established
     */
    public void openHouseConnection(String host, int port,
                                    Consumer<String> onNotification) throws IOException {
        // Close any existing house connection cleanly.
        if (houseSocket != null && !houseSocket.isClosed()) {
            try { houseSocket.close(); } catch (IOException ignored) { }
        }
        responseQueue.clear();

        this.houseHost = host;
        this.housePort = port;

        houseSocket = new Socket(host, port);
        housePersistentOut = new PrintWriter(houseSocket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(houseSocket.getInputStream()));

        // Consume the two welcome lines the AuctionHouse sends on connect.
        in.readLine();
        in.readLine();

        // Listener: routes push notifications to the callback,
        // everything else to the response queue for command callers.
        Thread listener = new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("OUTBID") || line.startsWith("WINNER")) {
                        onNotification.accept(line);
                    } else {
                        responseQueue.put(line);
                    }
                }
            } catch (IOException | InterruptedException ignored) { }
        });
        listener.setDaemon(true);
        listener.start();
    }

    /**
     * Requests active items from the connected AuctionHouse.
     *
     * @return item list response
     * @throws IOException          if not connected to a house
     * @throws InterruptedException if the calling thread is interrupted while waiting for the response
     */
    public String listItems() throws IOException, InterruptedException {
        if (housePersistentOut == null) {
            return "ERROR no auction house connected";
        }
        synchronized (housePersistentOut) {
            housePersistentOut.println("LIST_ITEMS");
        }
        String response = responseQueue.poll(5, TimeUnit.SECONDS);
        return response != null ? response : "ERROR no response from house";
    }

    /**
     * Places a bid on an item at the connected AuctionHouse.
     * The BID is sent through the persistent connection so the AuctionHouse
     * registers this connection's writer for OUTBID/WINNER notifications.
     *
     * @param itemId item ID
     * @param amount bid amount
     * @return bid response
     * @throws IOException          if not connected to a house
     * @throws InterruptedException if the calling thread is interrupted while waiting for the response
     */
    public String placeBid(int itemId, double amount) throws IOException, InterruptedException {
        if (housePersistentOut == null) {
            return "ERROR no auction house connected";
        }
        synchronized (housePersistentOut) {
            housePersistentOut.println("BID " + agentId + " " + accountId + " " + itemId + " " + amount);
        }
        String response = responseQueue.poll(5, TimeUnit.SECONDS);
        return response != null ? response : "ERROR no response from house";
    }

    /**
     * Asks the Bank to transfer the agent's blocked funds to the house account.
     * Called after receiving a WINNER notification.
     *
     * @param houseAccountId destination account id (from WINNER message)
     * @param amount         amount to transfer (from WINNER message)
     * @return Bank response
     * @throws IOException if Bank communication fails
     */
    public String transfer(String houseAccountId, String amount) throws IOException {
        return sendBankCommand("TRANSFER " + accountId + " " + houseAccountId + " " + amount);
    }

    /**
     * Notifies the AuctionHouse that the bank transfer is complete so it can
     * remove the sold item and relist a replacement.
     *
     * @param itemId the item id from the WINNER message
     */
    public void notifyTransferDone(String itemId) {
        if (housePersistentOut != null) {
            synchronized (housePersistentOut) {
                housePersistentOut.println("TRANSFER_DONE " + itemId);
            }
        }
    }

    /**
     * Sends one command to the Bank and returns one response.
     *
     * @param command Bank protocol command
     * @return Bank response
     * @throws IOException if communication fails
     */
    private String sendBankCommand(String command) throws IOException {
        try (Socket socket = new Socket(bankHost, bankPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            in.readLine();
            in.readLine();
            out.println(command);
            String response = in.readLine();
            out.println("QUIT");
            in.readLine();
            return response;
        }
    }

    /**
     * Parses: OK AGENT <agentId> ACCOUNT <accountId>.
     *
     * @param response raw Bank response
     */
    private void parseAgentRegistration(String response) {
        String[] parts = response.split("\\s+");
        agentId = Integer.parseInt(parts[2]);
        accountId = Integer.parseInt(parts[4]);
    }

    public int getAgentId()   { return agentId; }
    public int getAccountId() { return accountId; }
}