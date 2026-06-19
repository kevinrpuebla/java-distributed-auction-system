# Auction House

A distributed, multi-agent auction house where multiple agents can
concurrently bid on items across multiple auction houses, with a central bank
managing accounts, fund blocking, and transfers.

---

## Architecture

```
┌─────────┐        ┌──────────────┐        ┌─────────────────┐
│  Agent  │◄──────►│ AuctionHouse │◄──────►│      Bank       │
│ (GUI or │        │  (server +   │        │ (accounts, fund │
│console) │        │  bank client)│        │  blocking, xfer)│
└─────────┘        └──────────────┘        └─────────────────┘
```

The system has three independent processes:

- **Bank** — the central server which manages accounts, available/blocked funds,
  and transfers. All auction houses and agents register here on startup.
- **AuctionHouse** — lists items, accepts bids, runs 30-second countdown timers,
  and pushes `OUTBID` / `WINNER` notifications to agents over persistent
  connections. Each house is registered with the Bank on startup.
- **Agent** — a bidding agent that opens a bank account, discovers auction houses via the
  Bank, connects to a house, and places bids. Available as a console app
  (`Agent`) or a JavaFX GUI (`AgentGUI` + `AgentClient`).

---

## Files

| File | Role |
|---|---|
| `Bank.java` | Central bank server |
| `AuctionHouse.java` | Auction house server + bank client |
| `AuctionItem.java` | Thread-safe item model |
| `Agent.java` | Console-based bidding agent |
| `AgentClient.java` | Network backend for the GUI agent |
| `AgentGUI.java` | JavaFX GUI frontend for the agent |

---

## Compilation

Compile all files from the project root:

```bash
javac Bank.java AuctionItem.java AuctionHouse.java Agent.java AgentClient.java AgentGUI.java
```

If JavaFX is not on your module path, compile the GUI separately with the
appropriate `--module-path` and `--add-modules` flags for your JavaFX
installation.

---

## Running

Start the processes in this order: **Bank first**, then **AuctionHouse(s)** and
**Agent(s)** in either order.

### 1. Bank

```bash
java -jar Bank.jar <port>
```

| Argument | Description |
|---|---|
| `port` | Port the bank listens on |

**Example:**
```bash
java -jar Bank.jar 5000
```

---

### 2. AuctionHouse

```bash
java -jar AuctionHouse.jar <bankHost> <bankPort> <houseHost> <housePort> <houseName> <localListenPort> [itemsFile]
```

| Argument | Description |
|---|---|
| `bankHost` | Hostname or IP of the Bank |
| `bankPort` | Port the Bank is listening on |
| `houseHost` | Hostname or IP that agents use to reach this house |
| `housePort` | Port reported to the Bank (should match `localListenPort`) |
| `houseName` | Human-readable name shown to connecting agents |
| `localListenPort` | Port this house actually binds to |
| `itemsFile` | *(Optional)* Path to a text file of items to auction |

**Example — default items:**
```bash
java -jar AuctionHouse.jar localhost 5000 localhost 6000 "Gamestop" 6000
```

**Example — custom items file:**
```bash
java -jar AuctionHouse.jar localhost 5000 localhost 6000 "EBay" 6000 items.txt
```

#### Items file format

One item per line: `description,minimumBid`. Lines starting with `#` and blank
lines are ignored.

```
# Example items file
Laptop,200.0
Guitar,50.0
Watch,120.0
Bicycle,80.0
Camera,150.0
```

When the inventory is exhausted the house automatically reloads the file from
the top, so the auction continues indefinitely.

#### Safe shutdown

Type `SHUTDOWN` and press Enter in the auction house console. Shutdown is
refused if any bid timers are still running or any transfers are pending, to
prevent agents from losing funds.

---

### 3. Agent (console)

```bash
java -jar Agent.jar <bankHost> <bankPort> <name> <initialBalance>
```

| Argument | Description |
|---|---|
| `bankHost` | Hostname or IP of the Bank |
| `bankPort` | Port the Bank is listening on |
| `name` | Display name for this agent |
| `initialBalance` | Starting account balance |

**Example:**
```bash
java -jar Agent.jar localhost 5000 Kevin 500
```

The console menu lets you check your balance, list registered auction houses,
connect to a house, and place bids. You cannot quit while you are the current
highest bidder on any item.

---

### 4. Agent GUI

```bash
java AgentGUI
```

*(No arguments — all connection details are entered in the GUI.)*

If JavaFX is not on your module path:

```bash
java --module-path /path/to/javafx/lib --add-modules javafx.controls,javafx.fxml AgentGUI
```

#### GUI walkthrough

1. Enter **Bank Host** and **Port**, choose a **Name** and **Starting Balance**,
   then click **Open Agent Account**.
2. Click **List Houses** to see registered auction houses.
3. Enter a **House Host** and **Port**, then click **Connect House**. The
   Messages panel confirms the connection with the house's name.
4. Click **List Items** to see what is currently up for auction.
5. Click **Bid on Item**, enter the **Item ID** and **Bid Amount**, then click
   **Submit Bid**.
6. **OUTBID** and **WINNER** notifications appear automatically in the Messages
   panel. Balances refresh immediately after each notification.

---

## Auction flow

```
Agent bids
    │
    ▼
AuctionHouse validates amount ──(if too low)──► REJECT
    │
    ▼
Bank blocks agent funds ───(if insufficient)──► REJECT
    │
    ▼
Item high bid updated; 30-second timer starts (or resets)
    │
    ├── Previous highest bidder unblocked + OUTBID notification sent
    │
    ▼
Timer expires — highest bidding agent is WINNER
    │
    ├── WINNER notification sent to highest bidding agent
    │       │
    │       ▼
    │   Agent calls TRANSFER on the Bank, then notifies house (TRANSFER_DONE)
    │
    └── If agent is unreachable, house self-transfers immediately
    │
    ▼
Sold item removed; next item from inventory listed automatically
```

---

## Bank protocol reference

The Bank speaks a plain-text line protocol. Each connection receives two
greeting lines, then accepts one command per line.

| Command | Description |
|---|---|
| `OPEN_AGENT <name> <balance>` | Open an agent account |
| `OPEN_HOUSE <host> <port> <name>` | Register an auction house |
| `LIST_HOUSES` | List all registered houses |
| `BALANCE <accountId>` | Show total, blocked, and available balance |
| `BLOCK <accountId> <amount>` | Reserve funds for an active bid |
| `UNBLOCK <accountId> <amount>` | Release reserved funds after an outbid |
| `TRANSFER <fromId> <toId> <amount>` | Move blocked funds to house account |
| `DEREG_HOUSE <houseId>` | Remove a house from the registry |
| `DEREG_AGENT <agentId>` | Deregister an agent on exit |
| `QUIT` | Close the connection |

---

## AuctionHouse protocol reference

| Command | Description |
|---|---|
| `LIST_ITEMS` | List active (not yet sold) items |
| `BID <agentId> <accountId> <itemId> <amount>` | Place a bid |
| `CHECK_BIDS <agentId>` | Count active high bids held by an agent |
| `TRANSFER_DONE <itemId>` | Confirm bank transfer after winning |
| `QUIT` | Close the connection |

`LIST_ITEMS` response format (semicolon-separated):
```
OK ITEMS houseId,itemId,description,minBid,currentBid,secondsLeft
```
`secondsLeft` is `-1` if no bids have been placed yet.

---

## Design notes

**Fund safety** — funds are always blocked before a bid is recorded and
unblocked immediately if the bid is rejected or overtaken. The house uses
`checkError()` after sending a `WINNER` notification to detect a broken socket;
if the write failed the house self-transfers so funds are never stranded.

**Concurrency** — `AuctionItem.trySetHighBid` fuses the validity check and the
state update into one `synchronized` block, preventing two simultaneous bids
from both passing the pre-check before either commits. Bank transfers between
two accounts always lock the lower account ID first to prevent deadlock.

**Inventory cycling** — when the auction house exhausts its item queue and an
items file was supplied, the file is reloaded automatically so the house never
goes dark.