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
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;


import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class Client extends Application {
    private Map<String, Integer> unreadGroupMessages = new HashMap<>();


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

    // Ajouter ces variables d'instance dans la classe Client
    private TabPane tabPane;
    private VBox chatTab;
    private VBox groupsTab;
    private ListView<String> groupListView;
    private ObservableList<String> groupList;
    private TextField groupNameField;
    private TextField groupMemberField;
    private ListView<String> groupMembersListView;
    private ObservableList<String> groupMembersList;
    private String currentGroupId;
    private TextArea groupChatArea;

    private HBox groupInputBox;
    private TextField groupMessageField;



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
        // Créer la structure principale à onglets
        tabPane = new TabPane();

        // Onglet Chat individuel
        chatTab = new VBox(10);
        chatTab.setPadding(new Insets(10));
        chatTab.getStyleClass().add("vbox");

        chatArea = new TextArea();
        chatArea.setEditable(false);
//        setWrapText en JavaFX est utilisée pour activer ou désactiver le retour à la ligne automatique dans des composants comme TextArea, Label, TextField
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
        //ListView est un composant graphique qui permet d'afficher une liste d'éléments
        contactListView = new ListView<>(contactList);
        //Cela ajoute un Listener qui se déclenche à chaque fois que la sélection change dans le ListView
        contactListView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                recipientField.setText(newValue.split(" ")[0]);
            }
        });

        chatTab.getChildren().addAll(
                new Label("Chat History:"),
                chatArea,
                new Label("To:"),
                recipientField,
                inputBox
        );

        // Onglet Groupes
        groupsTab = new VBox(10);
        groupsTab.setPadding(new Insets(10));
        groupsTab.getStyleClass().add("vbox");

        // Zone pour créer un groupe
        groupNameField = new TextField();
        groupNameField.setPromptText("Group Name");

        Button createGroupButton = new Button("Create Group");
        createGroupButton.setOnAction(e -> createGroup());

        HBox createGroupBox = new HBox(10);
        createGroupBox.getChildren().addAll(groupNameField, createGroupButton);
        createGroupBox.getStyleClass().add("hbox");

        // Liste des groupes
        groupList = FXCollections.observableArrayList();
        groupListView = new ListView<>(groupList);
        groupListView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                String[] parts = newValue.split(": ");
                if (parts.length > 1) {
                    String groupInfo = parts[1];
                    String[] groupParts = groupInfo.split(" \\(");
                    if (groupParts.length > 0) {
                        String[] idAndName = groupParts[0].split("-");
                        if (idAndName.length > 0) {
                            currentGroupId = idAndName[0].trim();
                            getGroupMembers(currentGroupId);
                            groupChatArea.clear();
                        }
                    }
                }
            }
        });

        // Zone pour la gestion des membres
        groupMemberField = new TextField();
        groupMemberField.setPromptText("Member Email");

        Button addMemberButton = new Button("Add Member");
        addMemberButton.setOnAction(e -> addGroupMember());

        Button removeMemberButton = new Button("Remove Member");
        removeMemberButton.setOnAction(e -> removeGroupMember());

        HBox memberManagementBox = new HBox(10);
        memberManagementBox.getChildren().addAll(groupMemberField, addMemberButton, removeMemberButton);
        memberManagementBox.getStyleClass().add("hbox");

        // Liste des membres du groupe
        groupMembersList = FXCollections.observableArrayList();
        groupMembersListView = new ListView<>(groupMembersList);

        // Zone de chat du groupe
        groupChatArea = new TextArea();
        groupChatArea.setEditable(false);
        //setWrapText en JavaFX est utilisée pour activer ou désactiver le retour à la ligne automatique dans des composants comme TextArea, Label, TextField
        groupChatArea.setWrapText(true);

        groupMessageField = new TextField();
        groupMessageField.setPromptText("Type your group message");

        Button sendGroupButton = new Button("Send to Group");
        sendGroupButton.setOnAction(e -> sendGroupMessage());

        groupInputBox = new HBox(10);
        groupInputBox.getChildren().addAll(groupMessageField, sendGroupButton);
        groupInputBox.getStyleClass().add("hbox");

        // Ajouter les éléments à l'onglet Groupes
        groupsTab.getChildren().addAll(
                new Label("Create Group:"),
                createGroupBox,
                new Label("Your Groups:"),
                groupListView,
                new Label("Group Members:"),
                memberManagementBox,
                groupMembersListView,
                new Label("Group Chat:"),
                groupChatArea,
                groupInputBox
        );

        // Bouton de déconnexion
        Button logoutButton = new Button("Logout");
        logoutButton.setOnAction(e -> logout());

        // VBox pour les contacts
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

        // Créer les onglets
        Tab privateTab = new Tab("Private Chat", chatTab);
        privateTab.setClosable(false);

        Tab groupTab = new Tab("Group Chat", groupsTab);
        groupTab.setClosable(false);

        tabPane.getTabs().addAll(privateTab, groupTab);

        // Layout principal
        HBox mainBox = new HBox(10);
        mainBox.getChildren().addAll(tabPane, contactBox);
        mainBox.getStyleClass().add("hbox");

        Scene scene = new Scene(mainBox, 800, 600);
        // url to text
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        primaryStage.setTitle("Chat - " + userEmail);
        primaryStage.setScene(scene);

        getContacts();
        getGroups();
        initializeGroupListViewListener();
        initializeFileButtons();
    }

    private void createGroup() {
        String groupName = groupNameField.getText();
        if (!groupName.isEmpty()) {
            System.out.println("Sending CREATE_GROUP command with name: " + groupName);
            writer.println("CREATE_GROUP|" + groupName);
            groupNameField.clear();
        } else {
            showAlert("Please enter a group name");
        }
    }

    private void addGroupMember() {
        if (currentGroupId != null && !groupMemberField.getText().isEmpty()) {
            writer.println("ADD_GROUP_MEMBER|" + currentGroupId + "|" + groupMemberField.getText());
            groupMemberField.clear();
        }
    }

    private void removeGroupMember() {
        String selectedMember = groupMembersListView.getSelectionModel().getSelectedItem();
        if (currentGroupId != null && selectedMember != null) {
            // Récupérer l'email du format "email@example.com (status)"
            String memberEmail = selectedMember.split(" \\(")[0];
            writer.println("REMOVE_GROUP_MEMBER|" + currentGroupId + "|" + memberEmail);
        } else {
            showAlert("Please select a member to remove");
        }
    }

    private void getGroups() {
        writer.println("GET_GROUPS");
    }

    private void getGroupMembers(String groupId) {
        writer.println("GET_GROUP_MEMBERS|" + groupId);
    }



    private void sendGroupMessage() {
        if (currentGroupId != null) {
            // Modifiez cette ligne pour accéder directement à groupMessageField
            String message = groupMessageField.getText();
            if (!message.isEmpty()) {
                writer.println("GROUP_MESSAGE|" + currentGroupId + "|" + message);
                groupChatArea.appendText("Me: " + message + "\n");
                groupMessageField.clear(); // Utiliser directement la référence
            }
        }
    }






    private void connectToServer() {
        try {
            socket = new Socket("localhost", 5003);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(socket.getOutputStream(), true);

            // Start message listener
            //receiveMessages est le nom de la méthode que vous souhaitez exécuter dans le nouveau thread.
            new Thread(this::receiveMessages).start();
        } catch (IOException e) {
            showError("Connection failed: " + e.getMessage());
        }
    }

    private void getGroupMessages(String groupId) {
        writer.println("GET_GROUP_MESSAGES|" + groupId);
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
        System.out.println("Received from server: " + message);

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
                contactList.add(parts[1]);
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

            // Dans la méthode handleServerMessage(), ajoutez ces cas dans le switch
            case "GROUP_CREATED":
                showAlert("Group created: " + parts[2]);
                getGroups();
                break;
            case "GROUP_MEMBER_ADDED":
                showAlert("Member added to group");
                getGroupMembers(parts[1]);
                break;
            case "GROUP_MEMBER_REMOVED":
                showAlert("Member removed from group");
                getGroupMembers(parts[1]);
                break;
            case "GROUPS_LIST":
                groupList.clear();
                for (int i = 1; i < parts.length; i++) {
                    String[] groupInfo = parts[i].split(",");
                    if (groupInfo.length >= 3) {
                        String groupId = groupInfo[0];
                        String groupName = groupInfo[1];
                        String creator = groupInfo[2];
                        groupList.add("Group: " + groupId + " - " + groupName + " (Created by: " + creator + ")");
                    }
                }
                break;
            case "GROUP_MEMBERS":
                groupMembersList.clear();
                String groupId = parts[1];
                for (int i = 2; i < parts.length; i++) {
                    String[] memberInfo = parts[i].split(",");
                    if (memberInfo.length >= 2) {
                        String email = memberInfo[0];
                        String status = memberInfo[1];
                        groupMembersList.add(email + " (" + status + ")");
                    }
                }
                break;
            case "GROUP_MESSAGE":
                if (parts.length >= 4) {
                    String fromGroupId = parts[1];
                    String sender = parts[2];
                    String content = parts[3];

                    if (fromGroupId.equals(currentGroupId)) {
                        // Display in current chat area if it's the active group
                        groupChatArea.appendText(sender + ": " + content + "\n");
                    } else {
                        // Increment unread message count for this group
                        int count = unreadGroupMessages.getOrDefault(fromGroupId, 0);
                        unreadGroupMessages.put(fromGroupId, count + 1);

                        // Update the group list display to show unread messages
                        updateGroupListDisplay();
                    }
                }
                break;
            case "ADDED_TO_GROUP":
                showAlert("You have been added to group: " + parts[2]);
                getGroups();
                break;
            case "REMOVED_FROM_GROUP":
                showAlert("You have been removed from group: " + parts[1]);
                getGroups();
                if (currentGroupId != null && currentGroupId.equals(parts[1])) {
                    currentGroupId = null;
                    groupMembersList.clear();
                    groupChatArea.clear();
                }
                break;

            case "GROUP_MESSAGES":
                if (parts.length >= 2) {
                    String groupId_GROUP_MESSAGES = parts[1];
                    if (groupId_GROUP_MESSAGES.equals(currentGroupId)) {
                        groupChatArea.clear();
                        for (int i = 2; i < parts.length; i++) {
                            String[] messageParts = parts[i].split(",");
                            if (messageParts.length >= 3) {
                                String sender = messageParts[0];
                                String content = messageParts[1];
                                String timestamp = messageParts[2];

                                if (messageParts.length >= 6 && content.startsWith("File: ")) {
                                    String fileName = messageParts[3];
                                    String fileType = messageParts[4];
                                    String fileData = messageParts[5];

                                    groupChatArea.appendText(sender + " (" + timestamp + "): ");
                                    addFileLink(groupChatArea, fileName, fileType, fileData);
                                    groupChatArea.appendText("\n");
                                } else {
                                    groupChatArea.appendText(sender + " (" + timestamp + "): " + content + "\n");
                                }
                            }
                        }
                    }
                }
                break;

            case "GROUP_FAILED":
                showError("Group operation failed: " + parts[1]);
                break;

            case "FILE":
                if (parts.length >= 5) {
                    String sender = parts[1];
                    String fileName = parts[2];
                    String fileType = parts[3];
                    String fileData = parts[4];
                    chatArea.appendText(sender + ": Sent " + fileType + ": " + fileName + "\n");
                    displayFile(fileName, fileType, fileData);
                }
                break;

            case "GROUP_FILE":
                if (parts.length >= 6) {
                    String groupId_GROUP_FILE = parts[1];
                    String sender = parts[2];
                    String fileName = parts[3];
                    String fileType = parts[4];
                    String fileData = parts[5];
                    if (groupId_GROUP_FILE.equals(currentGroupId)) {
                        groupChatArea.appendText(sender + ": Sent " + fileType + ": " + fileName + "\n");
                        displayFile(fileName, fileType, fileData);
                    }
                }
                break;


        }
    }


    private void initializeFileButtons() {
        Button sendImageButton = new Button("Send Image");
        Button sendAudioButton = new Button("Send Audio");
        Button sendVideoButton = new Button("Send Video");

        sendImageButton.setOnAction(e -> sendFile("image"));
        sendAudioButton.setOnAction(e -> sendFile("audio"));
        sendVideoButton.setOnAction(e -> sendFile("video"));

        HBox fileButtonsBox = new HBox(10);
        fileButtonsBox.getChildren().addAll(sendImageButton, sendAudioButton, sendVideoButton);

        // Add to private chat
        chatTab.getChildren().add(fileButtonsBox);

        // Fix: Create buttons separately before adding them to HBox
        Button sendImageGroupButton = new Button("Send Image to Group");
        sendImageGroupButton.setOnAction(e -> sendGroupFile("image"));

        Button sendAudioGroupButton = new Button("Send Audio to Group");
        sendAudioGroupButton.setOnAction(e -> sendGroupFile("audio"));

        Button sendVideoGroupButton = new Button("Send Video to Group");
        sendVideoGroupButton.setOnAction(e -> sendGroupFile("video"));

        HBox groupFileButtonsBox = new HBox(10);
        groupFileButtonsBox.getChildren().addAll(sendImageGroupButton, sendAudioGroupButton, sendVideoGroupButton);

        // Add to group chat
        groupsTab.getChildren().add(groupFileButtonsBox);
    }


    private void sendFile(String fileType) {
        String recipient = recipientField.getText();
        if (recipient.isEmpty()) {
            showAlert("Please select a recipient");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        switch (fileType) {
            case "image":
                fileChooser.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.gif")
                );
                break;
            case "audio":
                fileChooser.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter("Audio Files", "*.mp3", "*.wav")
                );
                break;
            case "video":
                fileChooser.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter("Video Files", "*.mp4", "*.avi")
                );
                break;
        }

        File file = fileChooser.showOpenDialog(primaryStage);
        if (file != null) {
            try {
                byte[] fileData = Files.readAllBytes(file.toPath());
                String base64Data = Base64.getEncoder().encodeToString(fileData);

                writer.println("FILE|" + recipient + "|" + file.getName() + "|" + fileType + "|" + base64Data);
                chatArea.appendText("Me -> " + recipient + ": Sent " + fileType + ": " + file.getName() + "\n");
            } catch (IOException e) {
                showError("Error sending file: " + e.getMessage());
            }
        }
    }

    private void sendGroupFile(String fileType) {
        if (currentGroupId == null) {
            showAlert("Please select a group");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        switch (fileType) {
            case "image":
                fileChooser.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.gif")
                );
                break;
            case "audio":
                fileChooser.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter("Audio Files", "*.mp3", "*.wav")
                );
                break;
            case "video":
                fileChooser.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter("Video Files", "*.mp4", "*.avi")
                );
                break;
        }

        File file = fileChooser.showOpenDialog(primaryStage);
        if (file != null) {
            try {
                byte[] fileData = Files.readAllBytes(file.toPath());
                String base64Data = Base64.getEncoder().encodeToString(fileData);

                writer.println("GROUP_FILE|" + currentGroupId + "|" + file.getName() + "|" + fileType + "|" + base64Data);
                groupChatArea.appendText("Me: Sent " + fileType + ": " + file.getName() + "\n");
            } catch (IOException e) {
                showError("Error sending file: " + e.getMessage());
            }
        }
    }





    // 2. Add this new method to update group list display with unread counts
    private void updateGroupListDisplay() {
        for (int i = 0; i < groupList.size(); i++) {
            String item = groupList.get(i);
            String[] parts = item.split(": ");
            if (parts.length > 1) {
                String groupInfo = parts[1];
                String[] groupParts = groupInfo.split(" \\(");
                if (groupParts.length > 0) {
                    String[] idAndName = groupParts[0].split("-");
                    if (idAndName.length > 0) {
                        String groupId = idAndName[0].trim();
                        int unreadCount = unreadGroupMessages.getOrDefault(groupId, 0);

                        // Only update display if there are unread messages
                        if (unreadCount > 0) {
                            String updatedItem = item;
                            if (!item.contains("[Unread: ")) {
                                updatedItem = item + " [Unread: " + unreadCount + "]";
                            } else {
                                // Update existing unread count
                                updatedItem = item.replaceAll("\\[Unread: \\d+\\]", "[Unread: " + unreadCount + "]");
                            }
                            groupList.set(i, updatedItem);
                        }
                    }
                }
            }
        }
    }

    // Inside the Client class, add this method to initialize the group list view listener
    private void initializeGroupListViewListener() {
        groupListView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                String[] parts = newValue.split(": ");
                if (parts.length > 1) {
                    String groupInfo = parts[1];
                    String[] groupParts = groupInfo.split(" \\(");
                    if (groupParts.length > 0) {
                        String[] idAndName = groupParts[0].split("-");
                        if (idAndName.length > 0) {
                            currentGroupId = idAndName[0].trim();

                            // Clear unread count for this group
                            unreadGroupMessages.put(currentGroupId, 0);

                            // Update the group list to remove [Unread] indicator
                            for (int i = 0; i < groupList.size(); i++) {
                                String item = groupList.get(i);
                                if (item.contains(currentGroupId) && item.contains("[Unread:")) {
                                    String updatedItem = item.replaceAll("\\s+\\[Unread: \\d+\\]", "");
                                    groupList.set(i, updatedItem);
                                }
                            }

                            getGroupMembers(currentGroupId);
                            getGroupMessages(currentGroupId);
                        }
                    }
                }
            }
        });
    }

    private void addFileLink(TextArea chatArea, String fileName, String fileType, String fileData) {
        Hyperlink fileLink = new Hyperlink(fileName);
        fileLink.setOnAction(e -> displayFile(fileName, fileType, fileData));

        // Create a temporary pane to hold the hyperlink
        AnchorPane tempPane = new AnchorPane(fileLink);

        // Insert the hyperlink at the current caret position
        Platform.runLater(() -> {
            int caretPosition = chatArea.getCaretPosition();
            chatArea.insertText(caretPosition, "[Click to open: ");
            chatArea.appendText("]");

            // Store file data for later use
            fileLink.setUserData(new String[]{fileData, fileType});
        });
    }

    private void displayFile(String fileName, String fileType, String fileData) {
        try {
            byte[] data = Base64.getDecoder().decode(fileData);
            File tempFile = File.createTempFile("received_", fileName);
            Files.write(tempFile.toPath(), data);

            switch (fileType) {
                case "image":
                    displayImage(tempFile);
                    break;
                case "video":
                    openWithDefaultApp(tempFile);
                    break;
                case "audio":
                    openWithDefaultApp(tempFile);
                    break;
                default:
                    openWithDefaultApp(tempFile);
            }
        } catch (IOException e) {
            showError("Error displaying file: " + e.getMessage());
        }
    }

    private void displayImage(File imageFile) {
        Platform.runLater(() -> {
            try {
                Image image = new Image(imageFile.toURI().toString());
                ImageView imageView = new ImageView(image);
                imageView.setFitHeight(400);
                imageView.setFitWidth(400);
                imageView.setPreserveRatio(true);

                Stage imageStage = new Stage();
                imageStage.setTitle("Image Viewer");
                ScrollPane scrollPane = new ScrollPane(imageView);
                imageStage.setScene(new Scene(scrollPane));
                imageStage.show();
            } catch (Exception e) {
                showError("Error displaying image: " + e.getMessage());
            }
        });
    }

    private void openWithDefaultApp(File file) {
        try {
            Desktop.getDesktop().open(file);
        } catch (IOException e) {
            showError("Error opening file: " + e.getMessage());
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
        //calls the start() method
        launch(args);
    }
}