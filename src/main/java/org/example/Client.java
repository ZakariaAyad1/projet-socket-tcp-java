package org.example;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import javafx.stage.Stage;


import java.io.*;
import java.net.*;

public class Client extends Application {
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private String userEmail;

    // GUI components
    private TextArea chatArea;
    private TextField messageField;
    private TextField recipientField;
    private TextField contactField;
    private ListView<String> contactListView;
    private ObservableList<String> contactList;
    private Stage primaryStage;


    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        showLoginScreen();
    }

    private void showLoginScreen() {
        VBox loginBox = new VBox(10);
        loginBox.setPadding(new Insets(10));
        loginBox.getStyleClass().add("vbox");

        TextField emailField = new TextField();
        emailField.setPromptText("Email");

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");

        Button loginButton = new Button("Login");
        Button registerButton = new Button("Register");

        loginBox.getChildren().addAll(
                new Label("Email:"), emailField,
                new Label("Password:"), passwordField,
                loginButton, registerButton
        );

        loginButton.setOnAction(e -> {
            connectToServer();
            login(emailField.getText(), passwordField.getText());
        });

        registerButton.setOnAction(e -> {
            connectToServer();
            register(emailField.getText(), passwordField.getText());
        });

        Scene scene = new Scene(loginBox, 300, 200);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        primaryStage.setTitle("Chat Login");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void showChatScreen() {
        VBox chatBox = new VBox(10);
        chatBox.setPadding(new Insets(10));
        chatBox.getStyleClass().add("vbox");

        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatArea.setWrapText(true);

        recipientField = new TextField();
        recipientField.setPromptText("Recipient Email");

        messageField = new TextField();
        messageField.setPromptText("Type your message");

        Button sendButton = new Button("Send");
        sendButton.setOnAction(e -> sendMessage());

        HBox inputBox = new HBox(10);
        inputBox.getChildren().addAll(messageField, sendButton);
        inputBox.getStyleClass().add("hbox");

        contactField = new TextField();
        contactField.setPromptText("Contact Email");

        Button addContactButton = new Button("Add Contact");
        addContactButton.setOnAction(e -> addContact());

        Button removeContactButton = new Button("Remove Contact");
        removeContactButton.setOnAction(e -> removeContact());

        contactList = FXCollections.observableArrayList();
        contactListView = new ListView<>(contactList);
        contactListView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                recipientField.setText(newValue.split(" ")[0]);
            }
        });

        Button logoutButton = new Button("Logout");
        logoutButton.setOnAction(e -> logout());

        VBox contactBox = new VBox(10);
        contactBox.getChildren().addAll(
                new Label("Contacts:"),
                contactField,
                addContactButton,
                removeContactButton,
                contactListView,
                logoutButton
        );
        contactBox.getStyleClass().add("vbox");

        HBox mainBox = new HBox(10);
        mainBox.getChildren().addAll(chatBox, contactBox);
        mainBox.getStyleClass().add("hbox");

        chatBox.getChildren().addAll(
                new Label("Chat History:"),
                chatArea,
                new Label("To:"),
                recipientField,
                inputBox
        );

        Scene scene = new Scene(mainBox, 600, 500);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        primaryStage.setTitle("Chat - " + userEmail);
        primaryStage.setScene(scene);

        getContacts();
    }






    private void connectToServer() {
        try {
            socket = new Socket("localhost", 5003);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(socket.getOutputStream(), true);

            // Start message listener
            new Thread(this::receiveMessages).start();
        } catch (IOException e) {
            showError("Connection failed: " + e.getMessage());
        }
    }

    private void register(String email, String password) {
        writer.println("REGISTER|" + email + "|" + password);
    }

    private void login(String email, String password) {
        writer.println("LOGIN|" + email + "|" + password);
        userEmail = email;
    }

    private void sendMessage() {
        String recipient = recipientField.getText();
        String message = messageField.getText();

        if (!recipient.isEmpty() && !message.isEmpty()) {
            writer.println("MESSAGE|" + recipient + "|" + message);
            chatArea.appendText("Me -> " + recipient + ": " + message + "\n");
            messageField.clear();
        }
    }

    private void addContact() {
        String contactEmail = contactField.getText();
        if (!contactEmail.isEmpty()) {
            writer.println("ADD_CONTACT|" + contactEmail);
        }
    }

    private void removeContact() {
        String contactEmail = contactField.getText();
        if (!contactEmail.isEmpty()) {
            writer.println("REMOVE_CONTACT|" + contactEmail);
        }
    }

    private void getContacts() {
        writer.println("GET_CONTACTS");
    }

    private void logout() {
        writer.println("LOGOUT");
        try {
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        Platform.exit();
    }

    private void receiveMessages() {
        try {
            String message;
            while ((message = reader.readLine()) != null) {
                String finalMessage = message;
                Platform.runLater(() -> handleServerMessage(finalMessage));
            }
        } catch (IOException e) {
            Platform.runLater(() -> showError("Connection lost: " + e.getMessage()));
        }
    }

    private void handleServerMessage(String message) {
        String[] parts = message.split("\\|");
        String command = parts[0];

        switch (command) {
            case "REGISTER_SUCCESS":
                showAlert("Registration successful! Please login.");
                break;
            case "REGISTER_FAILED":
                showError("Registration failed: " + parts[1]);
                break;
            case "LOGIN_SUCCESS":
                showChatScreen();
                break;
            case "LOGIN_FAILED":
                showError("Login failed: " + parts[1]);
                break;
            case "MESSAGE":
                chatArea.appendText(parts[1] + ": " + parts[2] + "\n");
                break;
            case "OFFLINE_MESSAGE":
                chatArea.appendText("[Offline] " + parts[1] + " (" + parts[3] + "): " + parts[2] + "\n");
                break;
            case "CONTACT_ADDED":
                contactList.add(parts[1] + " (offline)");
                break;
            case "CONTACT_REMOVED":
                contactList.removeIf(contact -> contact.startsWith(parts[1]));
                break;
            case "CONTACTS_LIST":
                contactList.clear();
                for (int i = 1; i < parts.length; i++) {
                    contactList.add(parts[i]);
                }
                break;
            case "CONTACT_FAILED":
                showError("Contact operation failed: " + parts[1]);
                break;
        }
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Information");
        alert.setContentText(message);
        alert.showAndWait();
    }

    @Override
    public void stop() {
        if (writer != null) {
            writer.println("LOGOUT");
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}