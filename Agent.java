import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Agent represents a bidder in the distributed auction system.
 * Responsibilities:
 * - Open a bank account
 * - Query balance and available funds
 * - Discover auction houses via the bank
 * - Connect to auction houses
 * - View items and place bids
 */
public class Agent {

	private static String bankHost;
	private static int bankPort;

	private static int agentId;
	private static int accountId;
	// Stores houses visited in this run so quit can verify active bids.
	private static final List<String[]> KNOWN_HOUSES = new ArrayList<>();

	/**
	 * Program entry point.
	 * @param args bankHost, bankPort, name, initialBalance
	 * @throws IOException if a network call fails
	 */
	public static void main(String[] args) throws IOException {
		if (args.length < 4) {
			System.out.println("Usage: java Agent <bankHost> <bankPort> <name> <initialBalance>");
			return;
		}

		bankHost = args[0];
		bankPort = Integer.parseInt(args[1]);
		String name = args[2];
		double initialBalance = Double.parseDouble(args[3]);

		String openResponse = sendBankCommand("OPEN_AGENT " + name + " " + initialBalance);
		System.out.println("Bank response: " + openResponse);

		if (!openResponse.startsWith("OK AGENT")) {
			System.out.println("Could not open agent account.");
			return;
		}

		parseAgentRegistration(openResponse);
		System.out.println("Agent ID: " + agentId);
		System.out.println("Account ID: " + accountId);

		runMenu();
	}

	/**
	 * Shows the main menu and handles user choices.
	 * @throws IOException if a network call fails
	 */
	private static void runMenu() throws IOException {
		Scanner scanner = new Scanner(System.in);

		while (true) {
			System.out.println();
			System.out.println("1. Show balance");
			System.out.println("2. List auction houses");
			System.out.println("3. Connect to auction house");
			System.out.println("4. Quit");
			System.out.print("Choose: ");

			String choice = scanner.nextLine().trim();

			if ("1".equals(choice)) {
				String response = sendBankCommand("BALANCE " + accountId);
				System.out.println(response);

			} else if ("2".equals(choice)) {
				String response = sendBankCommand("LIST_HOUSES");
				if ("OK HOUSES NONE".equals(response) || !response.startsWith("OK HOUSES ")) {
					System.out.println("No auction houses are currently registered.");
				} else {
					String[] entries = response.substring("OK HOUSES ".length()).split(";");
					System.out.println("Registered auction houses:");
					for (String entry : entries) {
						String[] fields = entry.split(",", 4);
						System.out.printf("  [ID %s] %s  %s:%s%n",
								fields[0], fields.length > 3 ? fields[3] : "?", fields[1], fields[2]);
					}
				}

			} else if ("3".equals(choice)) {
				connectToAuctionHouse(scanner);

			} else if ("4".equals(choice)) {
				if (hasActiveBids()) {
					System.out.println("Cannot quit: you are still the high bidder on one or more items. Wait for the auction to resolve.");
				} else {
					sendBankCommand("DEREG_AGENT " + agentId);
					System.out.println("Goodbye.");
					break;
				}

			} else {
				System.out.println("Invalid choice.");
			}
		}
	}

	/**
	 * Connects to one auction house and runs the local bidding menu.
	 * A background thread prints asynchronous notifications like OUTBID/WINNER.
	 * All writes to the shared PrintWriter are synchronized so the main thread
	 * and the reader thread never interleave their output.
	 * @param scanner console scanner
	 * @throws IOException if the house cannot be reached
	 */
	private static void connectToAuctionHouse(Scanner scanner) throws IOException {
		String houseHost;
		int housePort;

		// Fetch the current house list from Bank so the user can pick by number.

		String listResp = sendBankCommand("LIST_HOUSES");
		if (!"OK HOUSES NONE".equals(listResp) && listResp.startsWith("OK HOUSES ")) {
			String[] entries = listResp.substring("OK HOUSES ".length()).split(";");
			System.out.println("Available auction houses:");
			for (int i = 0; i < entries.length; i++) {
				String[] fields = entries[i].split(",", 4);
				System.out.printf("  %d. %s  (host=%s port=%s)%n",
						i + 1, fields.length > 3 ? fields[3] : "?", fields[1], fields[2]);
			}
			System.out.print("Select house number (or 0 to enter manually): ");
			int pick = Integer.parseInt(scanner.nextLine().trim());
			if (pick >= 1 && pick <= entries.length) {
				String[] fields = entries[pick - 1].split(",", 4);
				houseHost = fields[1];
				housePort = Integer.parseInt(fields[2]);
			} else if (pick == 0) {
				System.out.print("Host: ");
				houseHost = scanner.nextLine().trim();
				System.out.print("Port: ");
				housePort = Integer.parseInt(scanner.nextLine().trim());
			} else {
				System.out.println("Invalid selection.");
				return;
			}
		} else {
			System.out.println("No auction houses are currently registered.");
			System.out.print("Enter host manually (or press Enter to cancel): ");
			houseHost = scanner.nextLine().trim();
			if (houseHost.isEmpty()) return;
			System.out.print("Port: ");
			housePort = Integer.parseInt(scanner.nextLine().trim());
		}

		// Remember this endpoint for quit-time active bid checks.

		KNOWN_HOUSES.add(new String[]{houseHost, String.valueOf(housePort)});

		try (Socket socket = new Socket(houseHost, housePort);
			 PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
			 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

			// Protocol sends two welcome lines.

			System.out.println(in.readLine());
			System.out.println(in.readLine());

			// Command responses go to this queue; notifications are handled inline.
			BlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();

			// Background thread: reads every line from the house socket.
			// OUTBID/WINNER are push notifications handled here.
			// Everything else is a command response placed on the queue.
			Thread reader = new Thread(() -> {
				try {
					String line;
					while ((line = in.readLine()) != null) {
						if (line.startsWith("OUTBID")) {
							System.out.println("\n*** NOTIFICATION: " + line + " ***");
						} else if (line.startsWith("WINNER")) {
							System.out.println("\n*** NOTIFICATION: " + line + " ***");
							String itemIdStr      = extractField(line, "item");
							String amountStr      = extractField(line, "amount");
							String houseAccountStr = extractField(line, "houseAccount");
							if (itemIdStr != null && amountStr != null && houseAccountStr != null) {
								try {
									String transferResp = sendBankCommand(
											"TRANSFER " + accountId + " " + houseAccountStr + " " + amountStr);
									System.out.println("Transfer result: " + transferResp);
									// Notify the house so it can relist the item.
									synchronized (out) {
										out.println("TRANSFER_DONE " + itemIdStr);
									}
								} catch (IOException e) {
									System.out.println("Transfer error: " + e.getMessage());
								}
							}
						} else {
							responseQueue.put(line);
						}
					}
				} catch (IOException | InterruptedException ignored) {
				}
			});
			reader.setDaemon(true);
			reader.start();

			while (true) {
				System.out.println();
				System.out.println("1. List items");
				System.out.println("2. Place bid");
				System.out.println("3. Leave auction house");
				System.out.print("Choose: ");

				String choice = scanner.nextLine().trim();

				if ("1".equals(choice)) {
					synchronized (out) {
						out.println("LIST_ITEMS");
					}
					try {
						String response = responseQueue.poll(5, TimeUnit.SECONDS);
						printItemsTable(response);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}

				} else if ("2".equals(choice)) {
					System.out.print("Item ID (or 0 to cancel): ");
					String idInput = scanner.nextLine().trim();
					if ("0".equals(idInput)) {
						System.out.println("Bid cancelled.");
					} else {
						int itemId = Integer.parseInt(idInput);
						System.out.print("Bid amount: ");
						double amount = Double.parseDouble(scanner.nextLine().trim());

						synchronized (out) {
							out.println("BID " + agentId + " " + accountId + " " + itemId + " " + amount);
						}
						try {
							String response = responseQueue.poll(5, TimeUnit.SECONDS);
							System.out.println(response != null ? response : "ERROR no response from house");
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					}

				} else if ("3".equals(choice)) {
					synchronized (out) {
						out.println("QUIT");
					}
					reader.interrupt();
					break;

				} else {
					System.out.println("Invalid choice.");
				}
			}
		}
	}

	/**
	 * Checks known houses for unresolved bids owned by this agent.
	 * @return true if at least one house reports active bids
	 */
	private static boolean hasActiveBids() {
		for (String[] house : KNOWN_HOUSES) {
			String houseHost = house[0];
			int housePort = Integer.parseInt(house[1]);
			try (Socket socket = new Socket(houseHost, housePort);
				 PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
				 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
				in.readLine();
				in.readLine();
				out.println("CHECK_BIDS " + agentId);
				String response = in.readLine();
				out.println("QUIT");
				in.readLine();

				// Expected format: OK ACTIVE_BIDS <count>

				if (response != null && response.startsWith("OK ACTIVE_BIDS")) {
					String[] parts = response.split("\\s+");
					if (parts.length >= 3 && Integer.parseInt(parts[2]) > 0) {
						return true;
					}
				}
			} catch (IOException e) {

				// If a house is unreachable, skip it and continue checking others.

				System.out.println("Could not reach house " + houseHost + ":" + housePort + " for bid check.");
			}
		}
		return false;
	}

	/**
	 * Parses and prints a LIST_ITEMS response as a formatted table.
	 * Each item entry: houseId,itemId,description,minBid,currentBid,timeLeft
	 * @param response the raw server response string
	 */
	private static void printItemsTable(String response) {
		if (response == null) {
			System.out.println("ERROR no response from house");
			return;
		}
		if ("OK ITEMS NONE".equals(response)) {
			System.out.println("No items currently listed.");
			return;
		}
		if (!response.startsWith("OK ITEMS ")) {
			System.out.println(response);
			return;
		}

		String[] entries = response.substring("OK ITEMS ".length()).split(";");
		System.out.printf("%n%-6s %-20s %-14s %s%n", "ID", "Name", "Current Bid", "Time Left");
		System.out.println("-".repeat(52));
		for (String entry : entries) {

			// Format: houseId,itemId,description,minBid,currentBid,timeLeft

			String[] f = entry.split(",", 6);
			String id      = f.length > 1 ? f[1] : "?";
			String name    = f.length > 2 ? f[2] : "?";
			String curBid  = f.length > 4 ? "$" + f[4] : "?";
			String timeStr;
			if (f.length > 5) {
				long secs = Long.parseLong(f[5]);
				timeStr = secs < 0 ? "no bids yet" : secs + "s";
			} else {
				timeStr = "?";
			}
			System.out.printf("%-6s %-20s %-14s %s%n", id, name, curBid, timeStr);
		}
	}

	private static String extractField(String line, String key) {
		for (String token : line.split("\\s+")) {
			if (token.startsWith(key + "=")) {
				return token.substring(key.length() + 1);
			}
		}
		return null;
	}

	/**
	 * Parses: OK AGENT <agentId> ACCOUNT <accountId>.
	 * @param response raw bank response line
	 */
	private static void parseAgentRegistration(String response) {
		String[] parts = response.split("\\s+");
		agentId = Integer.parseInt(parts[2]);
		accountId = Integer.parseInt(parts[4]);
	}

	/**
	 * Sends one request to the Bank and returns one response line.
	 * @param command bank protocol command
	 * @return bank response line
	 * @throws IOException if the bank connection fails
	 */
	private static String sendBankCommand(String command) throws IOException {
		try (Socket socket = new Socket(bankHost, bankPort);
			 PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
			 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

			// Bank protocol starts with two welcome lines.
			in.readLine();
			in.readLine();

			// Send one command and read one reply.
			out.println(command);
			String response = in.readLine();

			// End this short-lived request connection.

			out.println("QUIT");
			in.readLine();

			// End this short-lived request connection.

			return response;
		}
	}
}