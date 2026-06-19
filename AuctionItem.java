/**
 * Represents a single item being auctioned by an AuctionHouse. (Multiple
 * allowed)
 * Each item has:
 * - a given unique ID
 * - description
 * - minimum bid
 * - current highest bid
 * - current highest bidder--bookkeeping the agent and given amount
 */

public class AuctionItem {

    private final int itemId;
    private final String description;
    private final double minimumBid;

    // Mutable bidding state.
    private double currentBid;
    private int highBidderAgentId;
    private int highBidderAccountId;

    /**
     * Creates a new auction item with no bids yet.
     *
     * @param itemId      unique identifier for the item
     * @param description human-readable description
     * @param minimumBid  lowest acceptable first bid
     */
    public AuctionItem(int itemId, String description, double minimumBid) {
        this.itemId = itemId;
        this.description = description;
        this.minimumBid = minimumBid;
        this.currentBid = 0.0;
        this.highBidderAgentId = -1;
        this.highBidderAccountId = -1;
    }

    /** @return unique item id */
    public int getItemId() {
        return itemId;
    }

    /** @return item description */
    public String getDescription() {
        return description;
    }

    /** @return minimum acceptable bid */
    public double getMinimumBid() {
        return minimumBid;
    }

    /** @return current highest bid amount (0.0 if no bids yet) */
    public synchronized double getCurrentBid() {
        return currentBid;
    }

    /** @return agent id of current high bidder, or -1 if none */
    public synchronized int getHighBidderAgentId() {
        return highBidderAgentId;
    }

    /** @return account id of current high bidder, or -1 if none */
    public synchronized int getHighBidderAccountId() {
        return highBidderAccountId;
    }

    /**
     * Returns a consistent snapshot of all three mutable fields in one lock
     * acquisition. Use this when you need all three values to be coherent —
     * for example, when deciding whether to unblock a previous bidder.
     *
     * @return array [currentBid, highBidderAgentId, highBidderAccountId]
     */
    public synchronized double[] getBidSnapshot() {
        return new double[]{currentBid, highBidderAgentId, highBidderAccountId};
    }

    /**
     * Atomically validates and records a new high bid.
     *
     * The check (canAcceptBid) and the update (setHighBid) are fused into a
     * single synchronized operation so no two threads can both pass the check
     * before either has written the new state.
     *
     * @param agentId   agent placing the bid
     * @param accountId bank account used for the bid
     * @param amount    proposed bid amount
     * @return true if the bid was accepted and recorded; false otherwise
     */
    public synchronized boolean trySetHighBid(int agentId, int accountId, double amount) {
        if (!canAcceptBidInternal(amount)) {
            return false;
        }
        this.highBidderAgentId = agentId;
        this.highBidderAccountId = accountId;
        this.currentBid = amount;
        return true;
    }

    /**
     * Checks whether a proposed bid meets the auction rules.
     * A bid must strictly exceed both the minimum bid and the current high bid.
     *
     * This is kept public so AuctionHouse can do a quick pre-check before
     * contacting the bank, but the authoritative atomic check happens inside
     * trySetHighBid.
     *
     * @param amount proposed bid amount
     * @return true if the amount would be a valid new high bid
     */
    public synchronized boolean canAcceptBid(double amount) {
        return canAcceptBidInternal(amount);
    }

    // Private helper — called under the lock from both canAcceptBid and trySetHighBid.
    private boolean canAcceptBidInternal(double amount) {
        return amount > minimumBid && amount > currentBid;
    }

    /**
     * Serializes this item for LIST_ITEMS protocol responses.
     * Format: houseId,itemId,description,minimumBid,currentBid
     *
     * @param houseId the hosting auction house id
     * @return protocol-formatted string
     */
    public synchronized String toProtocolString(int houseId) {
        return houseId + "," + itemId + "," + description + ","
                + minimumBid + "," + currentBid;
    }
}