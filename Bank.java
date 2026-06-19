import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bank is the central account and registration server.
 * It manages accounts, blocked funds, transfers, and house discovery.
 */
public class Bank {
	// In-memory account store: accountId -> account.
	private static final Map<Integer, Account> ACCOUNTS = new ConcurrentHashMap<>();
	// Active house registry: houseId -> house info.
	private static final Map<Integer, AuctionHouseInfo> HOUSES = new ConcurrentHashMap<>();
	// Simple id generators.
	private static final AtomicInteger NEXT_ACCOUNT_ID = new AtomicInteger(1000);
	private static final AtomicInteger NEXT_AGENT_ID = new AtomicInteger(1);
	private static final AtomicInteger NEXT_HOUSE_ID = new AtomicInteger(1);

	public static void main(String[] args) throws IOException {
		if (args.length < 1) {
			System.out.println("Usage: java Bank <port>");
			return;
		}

		int portNumber = Integer.parseInt(args[0]);
		System.out.println("Bank listening on port " + portNumber + " ...");

		// Accept loop: each connection is handled by a dedicated thread.
		try (ServerSocket serverSocket = new ServerSocket(portNumber)) {
			while (true) {
				Socket clientSocket = serverSocket.accept();
				ClientHandler handler = new ClientHandler(clientSocket);
				Thread t = new Thread(handler);
				t.start();
			}
		}
	}

	private static class ClientHandler implements Runnable {
		private final Socket clientSocket;
		private final PrintWriter out;
		private final BufferedReader in;

		public ClientHandler(Socket clientSocket) throws IOException {
			this.clientSocket = clientSocket;
			out = new PrintWriter(clientSocket.getOutputStream(), true);
			in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
		}

		public void run() {
			try {
				out.println("WELCOME BANK");
				out.println("Type HELP for commands.");

				// Read commands line-by-line until disconnect or QUIT.
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
				try {
					clientSocket.close();
				} catch (IOException ignored) {
				}
			}
		}

		private String handleCommand(String commandLine) {
			if (commandLine.isEmpty()) {
				return "ERROR empty command";
			}

			// Protocol format: COMMAND arg1 arg2 ...
			String[] parts = commandLine.split("\\s+");
			String command = parts[0].toUpperCase();

			if ("HELP".equals(command)) {
				return "COMMANDS OPEN_AGENT OPEN_HOUSE LIST_HOUSES BALANCE BLOCK UNBLOCK TRANSFER DEREG_HOUSE DEREG_AGENT PING QUIT";
			}

			if ("PING".equals(command)) {
				return "PONG";
			}

			if ("QUIT".equals(command)) {
				return "BYE";
			}

			if ("OPEN_AGENT".equals(command)) {
				if (parts.length < 3) {
					return "ERROR usage: OPEN_AGENT <name> <initialBalance>";
				}
				String name = parts[1];
				double initialBalance;
				try {
					initialBalance = Double.parseDouble(parts[2]);
				} catch (NumberFormatException e) {
					return "ERROR invalid initialBalance";
				}
				if (initialBalance < 0) {
					return "ERROR initialBalance must be >= 0";
				}

				int accountId = NEXT_ACCOUNT_ID.getAndIncrement();
				int agentId = NEXT_AGENT_ID.getAndIncrement();
				// Agent accounts start with the requested opening balance.
				ACCOUNTS.put(accountId, new Account(accountId, name, initialBalance));
				return "OK AGENT " + agentId + " ACCOUNT " + accountId;
			}

			if ("OPEN_HOUSE".equals(command)) {
				if (parts.length < 4) {
					return "ERROR usage: OPEN_HOUSE <host> <port> <name>";
				}
				String host = parts[1];
				int port;
				try {
					port = Integer.parseInt(parts[2]);
				} catch (NumberFormatException e) {
					return "ERROR invalid port";
				}
				String name = parts[3];

				int accountId = NEXT_ACCOUNT_ID.getAndIncrement();
				int houseId = NEXT_HOUSE_ID.getAndIncrement();
				// House accounts always start at zero.
				ACCOUNTS.put(accountId, new Account(accountId, name, 0.0));
				HOUSES.put(houseId, new AuctionHouseInfo(houseId, host, port, accountId, name));
				return "OK HOUSE " + houseId + " ACCOUNT " + accountId;
			}

			if ("LIST_HOUSES".equals(command)) {
				if (HOUSES.isEmpty()) {
					return "OK HOUSES NONE";
				}

				// Example response format:
				// OK HOUSES houseId,host,port,name;houseId,host,port,name
				StringBuilder sb = new StringBuilder("OK HOUSES ");
				boolean first = true;
				for (AuctionHouseInfo house : HOUSES.values()) {
					if (!first) {
						sb.append(";");
					}
					sb.append(house.houseId)
							.append(",")
							.append(house.host)
							.append(",")
							.append(house.port)
							.append(",")
							.append(house.name);
					first = false;
				}
				return sb.toString();
			}

			if ("BALANCE".equals(command)) {
				if (parts.length < 2) {
					return "ERROR usage: BALANCE <accountId>";
				}
				int accountId;
				try {
					accountId = Integer.parseInt(parts[1]);
				} catch (NumberFormatException e) {
					return "ERROR invalid accountId";
				}

				Account account = ACCOUNTS.get(accountId);
				if (account == null) {
					return "ERROR account not found";
				}
				return "OK BALANCE total = $"   + String.format("%.2f", account.getTotalBalance())
						     + " blocked = $"   + String.format("%.2f", account.getBlockedFunds())
						     + " available = $" + String.format("%.2f", account.getAvailableFunds());
			}

			if ("BLOCK".equals(command)) {
				if (parts.length < 3) {
					return "ERROR usage: BLOCK <accountId> <amount>";
				}
				int accountId;
				double amount;
				try {
					accountId = Integer.parseInt(parts[1]);
					amount = Double.parseDouble(parts[2]);
				} catch (NumberFormatException e) {
					return "ERROR invalid accountId or amount";
				}
				Account account = ACCOUNTS.get(accountId);
				if (account == null) {
					return "ERROR account not found";
				}
				// Reserve funds for an active high bid.
				if (account.blockFunds(amount)) {
					return "OK BLOCKED";
				}
				return "ERROR insufficient funds";
			}

			if ("UNBLOCK".equals(command)) {
				if (parts.length < 3) {
					return "ERROR usage: UNBLOCK <accountId> <amount>";
				}
				int accountId;
				double amount;
				try {
					accountId = Integer.parseInt(parts[1]);
					amount = Double.parseDouble(parts[2]);
				} catch (NumberFormatException e) {
					return "ERROR invalid accountId or amount";
				}
				Account account = ACCOUNTS.get(accountId);
				if (account == null) {
					return "ERROR account not found";
				}
				// Release reserved funds after an outbid.
				if (account.unblockFunds(amount)) {
					return "OK UNBLOCKED";
				}
				return "ERROR invalid unblock amount";
			}

			if ("TRANSFER".equals(command)) {
				if (parts.length < 4) {
					return "ERROR usage: TRANSFER <agentAccountId> <houseAccountId> <amount>";
				}
				int fromId;
				int toId;
				double amount;
				try {
					fromId = Integer.parseInt(parts[1]);
					toId = Integer.parseInt(parts[2]);
					amount = Double.parseDouble(parts[3]);
				} catch (NumberFormatException e) {
					return "ERROR invalid accountId or amount";
				}

				Account from = ACCOUNTS.get(fromId);
				Account to   = ACCOUNTS.get(toId);
				if (from == null || to == null) return "ERROR account not found";

				// Always lock lower ID first to prevent deadlock.
				Account first  = fromId < toId ? from : to;
				Account second = fromId < toId ? to   : from;

				synchronized (first) {
					synchronized (second) {
						if (!from.consumeBlockedFunds(amount)) return "ERROR transfer failed";
						to.addFunds(amount);
					}
				}
				return "OK TRANSFERRED";
			}

			if ("DEREG_HOUSE".equals(command)) {
				if (parts.length < 2) {
					return "ERROR usage: DEREG_HOUSE <houseId>";
				}
				int houseId;
				try {
					houseId = Integer.parseInt(parts[1]);
				} catch (NumberFormatException e) {
					return "ERROR invalid houseId";
				}
				AuctionHouseInfo removed = HOUSES.remove(houseId);
				if (removed == null) {
					return "ERROR house not found";
				}
				return "OK HOUSE_DEREGISTERED";
			}

			if ("DEREG_AGENT".equals(command)) {
				return "OK AGENT_DEREGISTERED";
			}

			return "ERROR unknown command";
		}
	}

	private static class Account {
		private final int accountId;
		private final String ownerName;
		// totalBalance includes both available and blocked funds.
		private double totalBalance;
		// blockedFunds are reserved for active high bids.
		private double blockedFunds;

		public Account(int accountId, String ownerName, double totalBalance) {
			this.accountId = accountId;
			this.ownerName = ownerName;
			this.totalBalance = totalBalance;
			this.blockedFunds = 0.0;
		}

		public synchronized double getTotalBalance() {
			return totalBalance;
		}

		public synchronized double getBlockedFunds() {
			return blockedFunds;
		}

		public synchronized double getAvailableFunds() {
			long difference = Math.round(totalBalance*100) - Math.round(blockedFunds*100);
			return difference/100.0;
		}

		public synchronized boolean blockFunds(double amount) {
			// Amount must be positive and within available balance.
			if (amount <= 0) {
				return false;
			}
			if (getAvailableFunds() < amount) {
				return false;
			}
			long sum = Math.round(blockedFunds*100) + Math.round(amount*100);
			blockedFunds = sum/100.0;
			return true;
		}

		public synchronized boolean unblockFunds(double amount) {
			// Can only release funds that are currently blocked.
			if (amount <= 0) {
				return false;
			}
			if (blockedFunds < amount) {
				return false;
			}
			long difference = Math.round(blockedFunds*100) - Math.round(amount*100);
			blockedFunds = difference/100.0;
			return true;
		}

		public synchronized boolean consumeBlockedFunds(double amount) {
			// Finalize a sale: remove from blocked and total balance.
			if (amount <= 0) {
				return false;
			}
			if (blockedFunds < amount) {
				return false;
			}
			long newBlockedFunds = Math.round(blockedFunds*100) - Math.round(amount*100);
			blockedFunds = newBlockedFunds/100.0;
			long newTotalBalance = Math.round(totalBalance*100) - Math.round(amount*100);
			totalBalance = newTotalBalance/100.0;
			return true;
		}

		public synchronized void addFunds(double amount) {
			// House account receives transferred funds.
			long sum = Math.round(totalBalance*100) + Math.round(amount*100);
			totalBalance = sum/100.0;
		}
	}

	private static class AuctionHouseInfo {
		// Bank-side house record used by LIST_HOUSES.
		private final int houseId;
		private final String host;
		private final int port;
		private final int accountId;
		private final String name;

		public AuctionHouseInfo(int houseId, String host, int port, int accountId, String name) {
			this.houseId = houseId;
			this.host = host;
			this.port = port;
			this.accountId = accountId;
			this.name = name;
		}
	}
}
