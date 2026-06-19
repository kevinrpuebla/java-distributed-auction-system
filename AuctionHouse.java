import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AuctionHouse acts as both:
 * - A client of the Bank (registering, blocking/unblocking funds)
 * - A server for Agents (handling item queries and bids)
 * Responsibilities:
 * - Maintain and manage auction items
 * - Accept and validate bids
 * - Coordinate with the bank for financial operations
 * Each agent connection is handled in a separate thread (Error Handling)
 */
public class AuctionHouse {

	private static final Map<Integer, AuctionItem> ITEMS = new ConcurrentHashMap<>();
	private static final AtomicInteger NEXT_ITEM_ID = new AtomicInteger(1);

	// agentId -> socket writer, used for push notifications.
	private static final Map<Integer, PrintWriter> AGENT_WRITERS = new ConcurrentHashMap<>();
	// Active per-item timers (reset on each accepted higher bid).
	private static final Map<Integer, ScheduledFuture<?>> ITEM_TIMERS = new ConcurrentHashMap<>();
	// Items that have been won but are waiting for the agent to confirm transfer.
	private static final java.util.Set<Integer> PENDING_TRANSFERS =
			java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
	private static final ScheduledExecutorService TIMER_SERVICE = Executors.newScheduledThreadPool(4);
	// Inventory waiting to be listed. Items are promoted to active listings over time.
	private static final Queue<String[]> INVENTORY_QUEUE = new ConcurrentLinkedQueue<>();
	private static final int WIN_SECONDS = 30;
	private static final int INITIAL_LISTINGS = 3;

	private static String bankHost;
	private static int bankPort;
	private static int houseId;
	private static int houseAccountId;
	// True after a safe shutdown is initiated.
	private static volatile boolean shuttingDown = false;

	/**
	 * Startup
	 * Steps:
	 * 1. Parse command-line arguments
	 * 2. Load initial items (default or from file)
	 * 3. Register with the bank and obtain IDs
	 * 4. Start a server to accept agent connections
	 * @param args command-line arguments
	 * @throws IOException if network communication fails
	 */
	public static void main(String[] args) throws IOException {
		if (args.length < 6) {
			System.out.println("Usage: java AuctionHouse <bankHost> <bankPort> <houseHost> <housePort> <houseName> <localListenPort> [itemsFile]");
			return;
		}

		AuctionHouse.bankHost = args[0];
		AuctionHouse.bankPort = Integer.parseInt(args[1]);

		String houseHost = args[2];
		int housePort = Integer.parseInt(args[3]);
		String houseName = args[4];
		int localListenPort = Integer.parseInt(args[5]);

		if (args.length >= 7) {
			loadItemsFromFile(args[6]);
		} else {
			loadDefaultItems();
		}

		// List the first batch of items at startup.
		for (int i = 0; i < INITIAL_LISTINGS; i++) {
			listNextItem();
		}

		String registerResponse = sendBankCommand(
				"OPEN_HOUSE " + houseHost + " " + housePort + " " + houseName
		);

		System.out.println("Bank response: " + registerResponse);

		if (!registerResponse.startsWith("OK HOUSE")) {
			System.out.println("Could not register auction house.");
			return;
		}

		parseHouseRegistration(registerResponse);

		System.out.println("Auction house registered.");
		System.out.println("House ID: " + houseId);
		System.out.println("House account ID: " + houseAccountId);
		System.out.println("Auction house listening on port " + localListenPort + " ...");
		System.out.println("Type SHUTDOWN and press Enter to attempt a safe shutdown.");

		// Local console command for safe shutdown.
		Thread consoleThread = new Thread(() -> {
			try (java.util.Scanner sc = new java.util.Scanner(System.in)) {
				while (sc.hasNextLine()) {
					if ("SHUTDOWN".equalsIgnoreCase(sc.nextLine().trim())) {
						performShutdown();
						return;
					}
				}
			}
		});
		consoleThread.setDaemon(true);
		consoleThread.start();

		try (ServerSocket serverSocket = new ServerSocket(localListenPort)) {
			while (true) {
				Socket agentSocket = serverSocket.accept();
				Thread t = new Thread(new AgentHandler(agentSocket));
				t.start();
			}
		}
	}

	/**
	 * Loads default items when no file is supplied.
	 */
	private static void loadDefaultItems() {
		// Basic starter inventory for quick testing.
		addItem("PS5", 100.0);
		addItem("Cat", 75.0);
		addItem("Painting", 230.0);
	}

	/**
	 * Loads inventory from a text file.
	 * Expected line format: description,minimumBid
	 * @param fileName inventory file path
	 * @throws IOException if the file cannot be read
	 */
	private static void loadItemsFromFile(String fileName) throws IOException {
		try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
			String line;

			while ((line = reader.readLine()) != null) {
				line = line.trim();

				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] parts = line.split(",", 2);

				if (parts.length < 2) {
					// Skip malformed lines and continue.
					System.out.println("Skipping invalid item line: " + line);
					continue;
				}

				String description = parts[0].trim();
				double minimumBid = Double.parseDouble(parts[1].trim());

				addItem(description, minimumBid);
			}
		}
	}

	/**
	 * Parses: OK HOUSE <houseId> ACCOUNT <accountId>.
	 * @param response raw bank response line
	 */
	private static void parseHouseRegistration(String response) {
		String[] parts = response.split("\\s+");
		houseId = Integer.parseInt(parts[2]);
		houseAccountId = Integer.parseInt(parts[4]);
	}

	/**
	 * Adds one item to pending inventory.
	 * @param description item description
	 * @param minimumBid minimum accepted bid
	 */
	private static void addItem(String description, double minimumBid) {
		INVENTORY_QUEUE.add(new String[]{description, String.valueOf(minimumBid)});
	}

	/**
	 * Moves one item from inventory queue into active listings.
	 */
	private static void listNextItem() {
		String[] entry = INVENTORY_QUEUE.poll();
		if (entry == null) return;
		int itemId = NEXT_ITEM_ID.getAndIncrement();
		ITEMS.put(itemId, new AuctionItem(itemId, entry[0], Double.parseDouble(entry[1])));
		System.out.println("Listed item " + itemId + ": " + entry[0] + " (min bid: " + entry[1] + ")");
	}

	/**
	 * Attempts a safe shutdown.
	 * Shutdown is denied while any item still has an active timer.
	 */
	private static void performShutdown() {
		if (!ITEM_TIMERS.isEmpty()) {
			System.out.println("SHUTDOWN DENIED: " + ITEM_TIMERS.size() + " active bid timer(s) still running.");
			return;
		}
		if (!PENDING_TRANSFERS.isEmpty()) {
			System.out.println("SHUTDOWN DENIED: " + PENDING_TRANSFERS.size() + " transfer(s) pending agent confirmation.");
			return;
		}
		shuttingDown = true;
		System.out.println("Shutting down auction house...");
		try {
			String resp = sendBankCommand("DEREG_HOUSE " + houseId);
			System.out.println("Bank deregistration: " + resp);
		} catch (IOException e) {
			System.out.println("Could not deregister from bank: " + e.getMessage());
		}
		TIMER_SERVICE.shutdownNow();
		System.out.println("Auction house shut down.");
		System.exit(0);
	}

	/**
	 * Sends one request to the bank and returns one response line.
	 * @param command bank protocol command
	 * @return bank response line
	 * @throws IOException if the bank connection fails
	 */
	private static String sendBankCommand(String command) throws IOException {
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
	 * Resets the 30-second countdown for one item.
	 * @param itemId item id
	 */
	private static void startOrResetTimer(int itemId) {
		ScheduledFuture<?> existing = ITEM_TIMERS.get(itemId);
		if (existing != null) {
			existing.cancel(false);
		}
		ScheduledFuture<?> future = TIMER_SERVICE.schedule(
				() -> handleWinner(itemId), WIN_SECONDS, TimeUnit.SECONDS);
		ITEM_TIMERS.put(itemId, future);
	}

	/**
	 * Called when the 30-second timer expires for an item.
	 * Transfers the winning bid from the agent's account to the house account,
	 * notifies the winning agent, removes the sold item, and relists the next
	 * item from the inventory queue (if any).
	 * @param itemId the item that has been won
	 */
	private static void handleWinner(int itemId) {
		AuctionItem item = ITEMS.get(itemId);
		if (item == null) return;

		int winnerAgentId;
		int winnerAccountId;
		double winAmount;
		synchronized (item) {
			winnerAgentId = item.getHighBidderAgentId();
			winnerAccountId = item.getHighBidderAccountId();
			winAmount = item.getCurrentBid();
			if (winnerAgentId == -1) return;
		}

		System.out.println("Item " + itemId + " won by agent " + winnerAgentId + " for " + winAmount);

		// Mark item as waiting for agent-triggered transfer before relisting.
		PENDING_TRANSFERS.add(itemId);
		ITEM_TIMERS.remove(itemId);

		// Send WINNER to agent with enough info for it to call TRANSFER on the bank.
		// Format: WINNER item=<id> amount=<amount> houseAccount=<accountId>
		PrintWriter winnerWriter = AGENT_WRITERS.get(winnerAgentId);
		if (winnerWriter != null) {
			winnerWriter.println("WINNER item=" + itemId + " amount=" + winAmount
					+ " houseAccount=" + houseAccountId);
		} else {
			// Agent is gone; house falls back and does the transfer itself.
			System.out.println("Winner agent not connected; house self-transferring for item " + itemId);
			try {
				String resp = sendBankCommand(
						"TRANSFER " + winnerAccountId + " " + houseAccountId + " " + winAmount);
				System.out.println("Self-transfer result: " + resp);
			} catch (IOException e) {
				System.out.println("Self-transfer error: " + e.getMessage());
			}
			PENDING_TRANSFERS.remove(itemId);
			ITEMS.remove(itemId);
			listNextItem();
		}
	}

	/**
	 * Handles one agent socket connection.
	 */
	private static class AgentHandler implements Runnable {
		private final Socket socket;
		private PrintWriter out;
		private BufferedReader in;
		// Used to remove stale writers on disconnect.
		private volatile int connectedAgentId = -1;

		/**
		 * Handles communication with a single connected agent
		 * Runs in its own thread and processes agent commands
		 */
		public AgentHandler(Socket socket) {
			this.socket = socket;
		}

		public void run() {
			try {
				//Note: This runs in command NOT GUI yet but this can act as
				// the backend support for it when we get there
				out = new PrintWriter(socket.getOutputStream(), true);
				in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

				out.println("Greetings AUCTION_HOUSE");
				out.println("Commands: LIST_ITEMS, BID <agentId> <accountId> <itemId> <amount>, QUIT");

				String inputLine;
				while ((inputLine = in.readLine()) != null) {
					String response = handleCommand(inputLine.trim());
					out.println(response);

					if ("BYE".equals(response)) {
						break;
					}
				}
			} catch (IOException ignored) {
			} finally {
				// Remove stale notification writer for this connection's agent.
				if (connectedAgentId != -1) {
					AGENT_WRITERS.remove(connectedAgentId);
				}
				try {
					socket.close();
				} catch (IOException ignored) {
				}
			}
		}

		private String handleCommand(String commandLine) {
			if (commandLine.isEmpty()) {
				return "ERROR empty command";
			}

			String[] parts = commandLine.split("\\s+");
			String command = parts[0].toUpperCase();

			if ("LIST_ITEMS".equals(command)) {
				return listItems();
			}

			if ("BID".equals(command)) {
				if (parts.length < 5) {
					return "ERROR usage: BID <agentId> <accountId> <itemId> <amount>";
				}

				try {
					int agentId = Integer.parseInt(parts[1]);
					int accountId = Integer.parseInt(parts[2]);
					int itemId = Integer.parseInt(parts[3]);
					double amount = Double.parseDouble(parts[4]);

					return handleBid(agentId, accountId, itemId, amount);
				} catch (NumberFormatException e) {
					return "ERROR invalid bid arguments";
				}
			}

			if ("QUIT".equals(command)) {
				return "BYE";
			}

			if ("CHECK_BIDS".equals(command)) {
				if (parts.length < 2) {
					return "ERROR usage: CHECK_BIDS <agentId>";
				}
				int queryAgentId;
				try {
					queryAgentId = Integer.parseInt(parts[1]);
				} catch (NumberFormatException e) {
					return "ERROR invalid agentId";
				}
				long count = ITEMS.values().stream()
						.filter(i -> i.getHighBidderAgentId() == queryAgentId)
						.count();
				return "OK ACTIVE_BIDS " + count;
			}

			if ("TRANSFER_DONE".equals(command)) {
				if (parts.length < 2) {
					return "ERROR usage: TRANSFER_DONE <itemId>";
				}
				int doneItemId;
				try {
					doneItemId = Integer.parseInt(parts[1]);
				} catch (NumberFormatException e) {
					return "ERROR invalid itemId";
				}
				if (!PENDING_TRANSFERS.remove(doneItemId)) {
					return "ERROR no pending transfer for item " + doneItemId;
				}
				// Agent confirmed transfer; remove the sold item and list a replacement.
				ITEMS.remove(doneItemId);
				listNextItem();
				System.out.println("Transfer confirmed by agent for item " + doneItemId);
				return "OK TRANSFER_RECEIVED";
			}

			if ("SHUTDOWN".equals(command)) {
				performShutdown();
				return "OK SHUTTING_DOWN";
			}

			return "ERROR unknown command";
		}

		/**
		 * Builds a protocol string for active items.
		 * @return serialized list response
		 */
		private String listItems() {
			if (ITEMS.isEmpty()) {
				return "OK ITEMS NONE";
			}

			StringBuilder sb = new StringBuilder("OK ITEMS ");
			boolean first = true;

			for (AuctionItem item : ITEMS.values()) {
				if (!first) {
					sb.append(";");
				}

				// Append remaining seconds from the live timer (0 if no bidder yet).
				ScheduledFuture<?> timer = ITEM_TIMERS.get(item.getItemId());
				long secsLeft = (timer != null) ? Math.max(0, timer.getDelay(TimeUnit.SECONDS)) : -1;
				sb.append(item.toProtocolString(houseId)).append(",").append(secsLeft);
				first = false;
			}

			return sb.toString();
		}

		/**
		 * Processes a bid request from an agent.
		 * 1. Validate bid amount against auction rules
		 * 2. Request the bank to block funds
		 * 3. If successful:
		 *    - Unblock previous bidder's funds
		 *    - Update the current highest bid
		 * @param agentId ID of the bidding agent
		 * @param accountId bank account used for the bid
		 * @param itemId item being bid on
		 * @param amount bid amount
		 * @return protocol ACCEPT/REJECT/ERROR line
		 */
		private String handleBid(int agentId, int accountId, int itemId, double amount) {
			AuctionItem item = ITEMS.get(itemId);
			if (item == null) {
				return "REJECT item not found";
			}

			// Quick pre-check before the expensive bank round-trip.
			// The real atomic check happens inside trySetHighBid below.
			if (!item.canAcceptBid(amount)) {
				return "REJECT bid must exceed minimum bid and current bid";
			}

			// Contact bank outside any lock — I/O should never hold a monitor.
			try {
				String blockResponse = sendBankCommand("BLOCK " + accountId + " " + amount);
				if (!blockResponse.startsWith("OK BLOCKED")) {
					return "REJECT bank rejected funds: " + blockResponse;
				}
			} catch (IOException e) {
				return "ERROR could not contact bank";
			}

			// Snapshot old bidder state before we potentially overwrite it.
			int oldAgentId;
			int oldAccountId;
			double oldBid;

			synchronized (item) {
				oldAgentId = item.getHighBidderAgentId();
				oldAccountId = item.getHighBidderAccountId();
				oldBid = item.getCurrentBid();

				// Atomic check-and-update. If another bid arrived while we were at
				// the bank, our block is now stale — release it and reject.
				if (!item.trySetHighBid(agentId, accountId, amount)) {
					try {
						sendBankCommand("UNBLOCK " + accountId + " " + amount);
					} catch (IOException ignored) { }
					return "REJECT bid was overtaken while contacting bank";
				}

				// Register writer inside the lock so OUTBID/WINNER notifications
				// are never sent to a stale writer for this agent.
				AGENT_WRITERS.put(agentId, out);
				connectedAgentId = agentId;

				// Timer reset must be inside the lock so two rapid bids on the
				// same item cannot each schedule their own winner countdown.
				startOrResetTimer(itemId);
			}

			// Unblock and notify the previous high bidder outside the item lock —
			// these are I/O operations and do not need to hold the monitor.
			if (oldAccountId != -1 && oldBid > 0) {
				try {
					String unblockResponse = sendBankCommand("UNBLOCK " + oldAccountId + " " + oldBid);
					System.out.println("Old bidder unblocked: " + unblockResponse);
				} catch (IOException e) {
					System.out.println("WARN: could not unblock old bidder: " + e.getMessage());
				}
				PrintWriter oldWriter = AGENT_WRITERS.get(oldAgentId);
				if (oldWriter != null) {
					oldWriter.println("OUTBID item = " + itemId + " amount = $" + String.format("%.2f", amount));
				}
			}

			return "ACCEPT item = " + itemId + " amount = $" + String.format("%.2f", amount);
		}
	}
}