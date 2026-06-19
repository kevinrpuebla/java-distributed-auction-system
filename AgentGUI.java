import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/**
 * JavaFX graphical interface for the Agent.
 *
 * Provides a visual auction-style UI for viewing houses, viewing items,
 * checking balance, and placing bids.
 */
public class AgentGUI extends Application {

    private volatile AgentClient client;

    private final Label agentInfoLabel = new Label("Not connected");
    private final Label balanceLabel = new Label("Balance: unknown");
    private final TextArea outputArea = new TextArea();

    private final TextField bankHostField = new TextField("localhost");
    private final TextField bankPortField = new TextField("5000");
    private final TextField nameField = new TextField("Kevin");
    private final TextField balanceStartField = new TextField("500");

    private final TextField houseHostField = new TextField("localhost");
    private final TextField housePortField = new TextField("6000");

    private final TextField itemIdField = new TextField();
    private final TextField bidAmountField = new TextField();

    private final StackPane centerPane = new StackPane();

    /**
     * Starts the JavaFX GUI.
     *
     * @param stage primary window
     */
    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.setTop(buildTopPanel());
        root.setCenter(buildAuctionStage());
        root.setRight(buildNotificationPanel());
        root.setBottom(buildBottomPanel());

        Scene scene = new Scene(root, 1000, 650);
        stage.setTitle("Auction House Agent GUI");
        stage.setScene(scene);
        stage.show();
    }

    /**
     * Builds the top login and account panel.
     *
     * @return top UI panel
     */
    private VBox buildTopPanel() {
        HBox loginRow = new HBox(8);
        loginRow.setPadding(new Insets(10));
        loginRow.setAlignment(Pos.CENTER_LEFT);

        Button connectButton = new Button("Open Agent Account");
        connectButton.setOnAction(e -> openAgent());

        bankHostField.setPrefWidth(100);
        bankPortField.setPrefWidth(70);
        nameField.setPrefWidth(100);
        balanceStartField.setPrefWidth(80);

        loginRow.getChildren().addAll(
                new Label("Bank Host:"), bankHostField,
                new Label("Port:"), bankPortField,
                new Label("Name:"), nameField,
                new Label("Start $:"), balanceStartField,
                connectButton
        );

        HBox infoRow = new HBox(20);
        infoRow.setPadding(new Insets(0, 10, 10, 10));
        infoRow.getChildren().addAll(agentInfoLabel, balanceLabel);

        VBox box = new VBox(loginRow, infoRow);
        box.setStyle("-fx-background-color: #2b1d18;");
        agentInfoLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14;");
        balanceLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14;");
        return box;
    }

    /**
     * Builds the center auction stage area.
     *
     * @return center auction UI
     */
    private StackPane buildAuctionStage() {
        centerPane.setPadding(new Insets(25));
        centerPane.setStyle(
                "-fx-background-color: linear-gradient(to bottom, #5b1f1f, #2b1d18);" +
                        "-fx-border-color: #d4af37;" +
                        "-fx-border-width: 4;"
        );
        showAuctionView();
        return centerPane;
    }

    /**
     * Shows the main auction browsing view.
     */
    private void showAuctionView() {
        VBox card = new VBox(15);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(30));
        card.setMaxWidth(450);
        card.setStyle(
                "-fx-background-color: #fff0c9;" +
                        "-fx-border-color: #8b5a2b;" +
                        "-fx-border-width: 3;" +
                        "-fx-background-radius: 12;" +
                        "-fx-border-radius: 12;"
        );

        Label title    = new Label("Auction Stage");
        title.setStyle("-fx-font-size: 30; -fx-font-weight: bold;");

        Label subtitle = new Label("List items, select one, then place a bid.");
        subtitle.setStyle("-fx-font-size: 15;");

        Button listItemsButton = new Button("List Items");
        listItemsButton.setOnAction(e -> listItems());

        Button showBidButton = new Button("Bid on Item");
        showBidButton.setOnAction(e -> showBidView());

        card.getChildren().addAll(title, subtitle, listItemsButton, showBidButton);
        centerPane.getChildren().setAll(card);
    }

    /**
     * Shows the bid input form.
     */
    private void showBidView() {
        VBox card = new VBox(12);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(30));
        card.setMaxWidth(420);
        card.setStyle(
                "-fx-background-color: #fff0c9;" +
                        "-fx-border-color: #8b5a2b;" +
                        "-fx-border-width: 3;" +
                        "-fx-background-radius: 12;" +
                        "-fx-border-radius: 12;"
        );

        Label title = new Label("Place Bid");
        title.setStyle("-fx-font-size: 28; -fx-font-weight: bold;");

        itemIdField.setPromptText("Item ID");
        bidAmountField.setPromptText("Bid Amount");

        Button submitButton = new Button("Submit Bid");
        submitButton.setOnAction(e -> placeBid());

        Button cancelButton = new Button("Cancel");
        cancelButton.setOnAction(e -> showAuctionView());

        HBox buttons = new HBox(10, submitButton, cancelButton);
        buttons.setAlignment(Pos.CENTER);

        card.getChildren().addAll(
                title,
                new Label("Item ID:"), itemIdField,
                new Label("Bid Amount:"), bidAmountField,
                buttons
        );
        centerPane.getChildren().setAll(card);
    }

    /**
     * Builds the notification/output panel.
     *
     * @return right UI panel
     */
    private VBox buildNotificationPanel() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(10));
        box.setPrefWidth(330);

        Label title = new Label("Messages");
        title.setStyle("-fx-font-size: 18; -fx-font-weight: bold;");

        outputArea.setEditable(false);
        outputArea.setWrapText(true);

        box.getChildren().addAll(title, outputArea);
        return box;
    }

    /**
     * Builds the bottom controls for houses and balance.
     *
     * @return bottom UI panel
     */
    private HBox buildBottomPanel() {
        HBox box = new HBox(8);
        box.setPadding(new Insets(10));
        box.setAlignment(Pos.CENTER_LEFT);

        Button refreshBalanceButton = new Button("Refresh Balance");
        refreshBalanceButton.setOnAction(e -> refreshBalance());

        Button listHousesButton = new Button("List Houses");
        listHousesButton.setOnAction(e -> listHouses());

        Button connectHouseButton = new Button("Connect House");
        connectHouseButton.setOnAction(e -> connectHouse());

        houseHostField.setPrefWidth(110);
        housePortField.setPrefWidth(70);

        box.getChildren().addAll(
                refreshBalanceButton,
                listHousesButton,
                new Label("House Host:"), houseHostField,
                new Label("Port:"), housePortField,
                connectHouseButton
        );
        return box;
    }

    /**
     * Opens the agent account through AgentClient.
     */
    private void openAgent() {
        runBackground(() -> {
            String host        = bankHostField.getText().trim();
            int port           = Integer.parseInt(bankPortField.getText().trim());
            String name        = nameField.getText().trim();
            double startBalance = Double.parseDouble(balanceStartField.getText().trim());

            client = new AgentClient(host, port);
            return client.openAgent(name, startBalance);
        }, response -> {
            append(response);
            if (response.startsWith("OK AGENT")) {
                agentInfoLabel.setText("Agent ID: " + client.getAgentId()
                        + " | Account ID: " + client.getAccountId());
                refreshBalance();
            }
        });
    }

    /**
     * Refreshes the displayed Bank balance.
     */
    private void refreshBalance() {
        if (client == null) { append("ERROR open an agent account first"); return; }
        runBackground(() -> client.getBalance(), response -> {
            balanceLabel.setText(response);
            append(response);
        });
    }

    /**
     * Lists auction houses from the Bank.
     */
    private void listHouses() {
        if (client == null) { append("ERROR open an agent account first"); return; }
        runBackground(() -> client.listHouses(), this::append);
    }

    /**
     * Opens a persistent connection to the selected AuctionHouse and registers
     * a notification callback. OUTBID is displayed immediately; WINNER triggers
     * the bank transfer and house confirmation automatically, matching the
     * behaviour of the console Agent.
     */
    private void connectHouse() {
        if (client == null) {
            append("ERROR open an agent account first");
            return;
        }

        String host = houseHostField.getText().trim();
        int port    = Integer.parseInt(housePortField.getText().trim());

        runBackground(() -> {
            client.openHouseConnection(host, port, notification -> {
                append("*** " + notification + " ***");

                if (notification.startsWith("OUTBID")) {
                    refreshBalance();
                }
                else if (notification.startsWith("WINNER")) {
                    String itemId    = extractField(notification, "item");
                    String amount    = extractField(notification, "amount");
                    String houseAcct = extractField(notification, "houseAccount");

                    if (itemId != null && amount != null && houseAcct != null) {
                        try {
                            String transferResp = client.transfer(houseAcct, amount);
                            append("Transfer result: " + transferResp);
                            client.notifyTransferDone(itemId);
                            refreshBalance();
                        } catch (Exception e) {
                            append("Transfer error: " + e.getMessage());
                        }
                    }
                }
            });
            return "Connected to " + host + ":" + port;
        }, this::append);
    }

    /**
     * Lists items from the connected AuctionHouse.
     */
    private void listItems() {
        if (client == null) { append("ERROR open an agent account first"); return; }
        runBackground(() -> client.listItems(), this::append);
    }

    /**
     * Places a bid through the connected AuctionHouse.
     */
    private void placeBid() {
        if (client == null) { append("ERROR open an agent account first"); return; }
        runBackground(() -> {
            int itemId     = Integer.parseInt(itemIdField.getText().trim());
            double amount  = Double.parseDouble(bidAmountField.getText().trim());
            return client.placeBid(itemId, amount);
        }, response -> {
            append(response);
            refreshBalance();
            showAuctionView();
        });
    }

    /**
     * Extracts a key=value field from a notification line.
     * e.g. extractField("WINNER item=3 amount=150.0 houseAccount=1001", "item") -> "3"
     *
     * @param line notification string
     * @param key  field name
     * @return field value, or null if not found
     */
    private String extractField(String line, String key) {
        for (String token : line.split("\\s+")) {
            if (token.startsWith(key + "=")) {
                return token.substring(key.length() + 1);
            }
        }
        return null;
    }

    /**
     * Runs a network operation off the JavaFX UI thread.
     *
     * @param work      background operation
     * @param onSuccess UI update after success
     */
    private void runBackground(BackgroundWork work, SuccessHandler onSuccess) {
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return work.run();
            }
        };
        task.setOnSucceeded(e -> onSuccess.handle(task.getValue()));
        task.setOnFailed(e -> append("ERROR " + task.getException().getMessage()));

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Appends a message to the GUI output area.
     *
     * @param message message to display
     */
    private void append(String message) {
        outputArea.appendText(message + "\n");
    }

    private interface BackgroundWork {
        String run() throws Exception;
    }

    private interface SuccessHandler {
        void handle(String response);
    }

    /**
     * Launches the JavaFX app.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        launch(args);
    }
}