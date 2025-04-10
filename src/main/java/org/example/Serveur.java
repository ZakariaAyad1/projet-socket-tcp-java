package org.example;

import java.io.*;
import java.net.*;
import java.util.*;
import java.sql.*;

public class Serveur {
    private static final int PORT = 5003;
    private static HashMap<String, ClientHandler> clients = new HashMap<>();
    private static Connection dbConnection;

    public static void main(String[] args) {
        initializeDatabase();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server started on port " + PORT);

            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("New client connected: " + socket.getInetAddress());

                ClientHandler clientHandler = new ClientHandler(socket);
                Thread thread = new Thread(clientHandler);
                thread.start();
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void initializeDatabase() {
        try {
            // Supprimer la base de données existante si elle existe
            File dbFile = new File("chat.db");
//            if (dbFile.exists()) {
//                dbFile.delete();
//            }

            Class.forName("org.sqlite.JDBC");
            dbConnection = DriverManager.getConnection("jdbc:sqlite:chat.db");

            // Activer les clés étrangères
            Statement enableForeignKeys = dbConnection.createStatement();
            enableForeignKeys.execute("PRAGMA foreign_keys = ON");

            String createUsersTable = """
                CREATE TABLE IF NOT EXISTS users (
                    email TEXT PRIMARY KEY,
                    password TEXT NOT NULL,
                    status TEXT DEFAULT 'offline',
                    last_seen DATETIME DEFAULT CURRENT_TIMESTAMP
                )
            """;

            String createMessagesTable = """
    CREATE TABLE IF NOT EXISTS messages (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        sender TEXT NOT NULL,
        receiver TEXT NOT NULL,
        content TEXT NOT NULL,
        file_data BLOB,
        file_name TEXT,
        file_type TEXT,
        timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
        status TEXT DEFAULT 'sent',
        FOREIGN KEY (sender) REFERENCES users(email)
    )
""";

            String createContactsTable = """
                CREATE TABLE IF NOT EXISTS contacts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_email TEXT NOT NULL,
                    contact_email TEXT NOT NULL,
                    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (user_email) REFERENCES users(email),
                    FOREIGN KEY (contact_email) REFERENCES users(email),
                    UNIQUE(user_email, contact_email)
                )
            """;

            // Dans initializeDatabase() après la création des autres tables
            String createGroupsTable = """
    CREATE TABLE IF NOT EXISTS groups (
        group_id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        creator TEXT NOT NULL,
        creation_date DATETIME DEFAULT CURRENT_TIMESTAMP,
        FOREIGN KEY (creator) REFERENCES users(email)
    )
""";

            String createGroupMembersTable = """
    CREATE TABLE IF NOT EXISTS group_members (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        group_id INTEGER NOT NULL,
        member_email TEXT NOT NULL,
        join_date DATETIME DEFAULT CURRENT_TIMESTAMP,
        FOREIGN KEY (group_id) REFERENCES groups(group_id),
        FOREIGN KEY (member_email) REFERENCES users(email),
        UNIQUE(group_id, member_email)
    )
""";



            Statement stmt = dbConnection.createStatement();
            stmt.execute(createUsersTable);
            stmt.execute(createMessagesTable);
            stmt.execute(createContactsTable);
            stmt.execute(createGroupsTable);
            stmt.execute(createGroupMembersTable);
            System.out.println("Database initialized successfully");
        } catch (Exception e) {
            System.err.println("Database initialization error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    static class ClientHandler implements Runnable {
        private Socket socket;
        private BufferedReader reader;
        private PrintWriter writer;
        private String userEmail;

        private void handleFile(String recipient, String fileName, String fileType, String base64Data) {
            if (userEmail == null) {
                writer.println("FILE_FAILED|Not logged in");
                return;
            }

            try {
                // Store file in database
                PreparedStatement stmt = dbConnection.prepareStatement(
                        "INSERT INTO messages (sender, receiver, content, file_data, file_name, file_type) " +
                                "VALUES (?, ?, ?, ?, ?, ?)"
                );
                stmt.setString(1, userEmail);
                stmt.setString(2, recipient);
                stmt.setString(3, "File: " + fileName);
                stmt.setString(4, base64Data);
                stmt.setString(5, fileName);
                stmt.setString(6, fileType);
                stmt.executeUpdate();

                // Forward to online recipient
                ClientHandler recipientHandler = clients.get(recipient);
                if (recipientHandler != null) {
                    recipientHandler.writer.println("FILE|" + userEmail + "|" + fileName + "|" + fileType + "|" + base64Data);
                }

                writer.println("FILE_SENT|" + recipient);
            } catch (SQLException e) {
                writer.println("FILE_FAILED|" + e.getMessage());
                System.err.println("File handling failed: " + e.getMessage());
            }
        }

        private void handleGroupFile(String groupId, String fileName, String fileType, String base64Data) {
            if (userEmail == null) {
                writer.println("GROUP_FILE_FAILED|Not logged in");
                return;
            }

            try {
                // Verify if user is group member
                PreparedStatement checkMember = dbConnection.prepareStatement(
                        "SELECT member_email FROM group_members WHERE group_id = ? AND member_email = ?"
                );
                checkMember.setInt(1, Integer.parseInt(groupId));
                checkMember.setString(2, userEmail);
                ResultSet memberRs = checkMember.executeQuery();

                if (memberRs.next()) {
                    // Store file in database
                    PreparedStatement stmt = dbConnection.prepareStatement(
                            "INSERT INTO messages (sender, receiver, content, file_data, file_name, file_type) " +
                                    "VALUES (?, ?, ?, ?, ?, ?)"
                    );
                    stmt.setString(1, userEmail);
                    stmt.setString(2, "GROUP:" + groupId);
                    stmt.setString(3, "File: " + fileName);
                    stmt.setString(4, base64Data);
                    stmt.setString(5, fileName);
                    stmt.setString(6, fileType);
                    stmt.executeUpdate();

                    // Forward to all online group members
                    PreparedStatement getMembers = dbConnection.prepareStatement(
                            "SELECT member_email FROM group_members WHERE group_id = ? AND member_email != ?"
                    );
                    getMembers.setInt(1, Integer.parseInt(groupId));
                    getMembers.setString(2, userEmail);
                    ResultSet membersRs = getMembers.executeQuery();

                    while (membersRs.next()) {
                        String memberEmail = membersRs.getString("member_email");
                        ClientHandler memberHandler = clients.get(memberEmail);
                        if (memberHandler != null) {
                            memberHandler.writer.println("GROUP_FILE|" + groupId + "|" + userEmail + "|" +
                                    fileName + "|" + fileType + "|" + base64Data);
                        }
                    }

                    writer.println("GROUP_FILE_SENT|" + groupId);
                } else {
                    writer.println("GROUP_FILE_FAILED|Not a member of this group");
                }
            } catch (SQLException | NumberFormatException e) {
                writer.println("GROUP_FILE_FAILED|" + e.getMessage());
                System.err.println("Group file handling failed: " + e.getMessage());
            }
        }

        public ClientHandler(Socket socket) {
            this.socket = socket;
            try {
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                writer = new PrintWriter(socket.getOutputStream(), true);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            try {
                while (true) {
                    String message = reader.readLine();
                    if (message == null) break;

                    System.out.println("Received message: " + message);

                    String[] parts = message.split("\\|");
                    if (parts.length < 1) continue;

                    String command = parts[0];
                    try {
                        switch (command) {
                            case "REGISTER":
                                if (parts.length >= 3) {
                                    handleRegistration(parts[1], parts[2]);
                                }
                                break;
                            case "LOGIN":
                                if (parts.length >= 3) {
                                    handleLogin(parts[1], parts[2]);
                                }
                                break;
                            case "MESSAGE":
                                if (parts.length >= 3) {
                                    handleMessage(parts[1], parts[2]);
                                }
                                break;
                            case "LOGOUT":
                                handleLogout();
                                break;
                            case "ADD_CONTACT":
                                if (parts.length >= 2) {
                                    handleAddContact(parts[1]);
                                }
                                break;
                            case "REMOVE_CONTACT":
                                if (parts.length >= 2) {
                                    handleRemoveContact(parts[1]);
                                }
                                break;
                            case "GET_CONTACTS":
                                handleGetContacts();
                                break;



                            // Dans la méthode run(), dans le switch command
                            case "CREATE_GROUP":
                                if (parts.length >= 2) {
                                    handleCreateGroup(parts[1]);
                                }
                                break;
                            case "ADD_GROUP_MEMBER":
                                if (parts.length >= 3) {
                                    handleAddMember(parts[1], parts[2]);
                                }
                                break;
                            case "REMOVE_GROUP_MEMBER":
                                if (parts.length >= 3) {
                                    handleRemoveMember(parts[1], parts[2]);
                                }
                                break;
                            case "GET_GROUPS":
                                handleGetGroups();
                                break;
                            case "GET_GROUP_MEMBERS":
                                if (parts.length >= 2) {
                                    handleGetGroupMembers(parts[1]);
                                }
                                break;
                            case "GROUP_MESSAGE":
                                if (parts.length >= 3) {
                                    handleGroupMessage(parts[1], parts[2]);
                                }
                                break;

                            // Add this in the switch statement in ClientHandler's run() method
                            case "GET_GROUP_MESSAGES":
                                if (parts.length >= 2) {
                                    handleGetGroupMessages(parts[1]);
                                }
                                break;

                            case "FILE":
                                if (parts.length >= 5) {
                                    handleFile(parts[1], parts[2], parts[3], parts[4]);
                                }
                                break;

                            case "GROUP_FILE":
                                if (parts.length >= 5) {
                                    handleGroupFile(parts[1], parts[2], parts[3], parts[4]);
                                }
                                break;



                            default:
                                System.out.println("Unknown command: " + command);
                        }
                    } catch (ArrayIndexOutOfBoundsException e) {
                        System.err.println("Invalid message format: " + message);
                    }
                }
            } catch (IOException e) {
                System.err.println("org.example.Client handler error: " + e.getMessage());
            } finally {
                handleLogout();
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        private void handleRegistration(String email, String password) {
            try {
                PreparedStatement stmt = dbConnection.prepareStatement(
                        "INSERT INTO users (email, password, status, last_seen) VALUES (?, ?, 'offline', CURRENT_TIMESTAMP)"
                );
                stmt.setString(1, email);
                stmt.setString(2, password);
                stmt.execute();
                writer.println("REGISTER_SUCCESS");
                System.out.println("New user registered: " + email);
            } catch (SQLException e) {
                writer.println("REGISTER_FAILED|" + e.getMessage());
                System.err.println("Registration failed for " + email + ": " + e.getMessage());
            }
        }

        private void handleLogin(String email, String password) {
            try {
                // Vérifier les identifiants
                PreparedStatement checkStmt = dbConnection.prepareStatement(
                        "SELECT email FROM users WHERE email = ? AND password = ?"
                );
                checkStmt.setString(1, email);
                checkStmt.setString(2, password);
                ResultSet rs = checkStmt.executeQuery();

                if (rs.next()) {
                    userEmail = email;
                    clients.put(email, this);

                    // Mettre à jour le statut et last_seen
                    PreparedStatement updateStmt = dbConnection.prepareStatement(
                            "UPDATE users SET status = 'online', last_seen = CURRENT_TIMESTAMP WHERE email = ?"
                    );
                    updateStmt.setString(1, email);
                    updateStmt.execute();

                    writer.println("LOGIN_SUCCESS");
                    System.out.println("User logged in: " + email);

                    // Envoyer les messages hors ligne
                    sendOfflineMessages();
                } else {
                    writer.println("LOGIN_FAILED|Invalid credentials");
                    System.out.println("Login failed for: " + email);
                }
            } catch (SQLException e) {
                writer.println("LOGIN_FAILED|" + e.getMessage());
                System.err.println("Login error for " + email + ": " + e.getMessage());
            }
        }

        private void handleMessage(String recipient, String content) {
            if (userEmail == null) {
                writer.println("MESSAGE_FAILED|Not logged in");
                return;
            }

            try {
                PreparedStatement stmt = dbConnection.prepareStatement(
                        "INSERT INTO messages (sender, receiver, content, timestamp) VALUES (?, ?, ?, CURRENT_TIMESTAMP)"
                );
                stmt.setString(1, userEmail);
                stmt.setString(2, recipient);
                stmt.setString(3, content);
                stmt.execute();

                ClientHandler recipientHandler = clients.get(recipient);
                if (recipientHandler != null) {
                    recipientHandler.writer.println("MESSAGE|" + userEmail + "|" + content);
                    System.out.println("Message sent from " + userEmail + " to " + recipient);
                } else {
                    System.out.println("Offline message stored for " + recipient);
                }
            } catch (SQLException e) {
                writer.println("MESSAGE_FAILED|" + e.getMessage());
                System.err.println("Message delivery failed: " + e.getMessage());
            }
        }

        private void handleLogout() {
            if (userEmail != null) {
                try {
                    PreparedStatement stmt = dbConnection.prepareStatement(
                            "UPDATE users SET status = 'offline', last_seen = CURRENT_TIMESTAMP WHERE email = ?"
                    );
                    stmt.setString(1, userEmail);
                    stmt.execute();

                    System.out.println("User logged out: " + userEmail);
                } catch (SQLException e) {
                    System.err.println("Logout error for " + userEmail + ": " + e.getMessage());
                }
                clients.remove(userEmail);
                userEmail = null;
            }
        }

        private void sendOfflineMessages() {
            try {
                PreparedStatement stmt = dbConnection.prepareStatement(
                        "SELECT sender, content, timestamp FROM messages WHERE receiver = ? ORDER BY timestamp"
                );
                stmt.setString(1, userEmail);
                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    String sender = rs.getString("sender");
                    String content = rs.getString("content");
                    String timestamp = rs.getString("timestamp");
                    writer.println("OFFLINE_MESSAGE|" + sender + "|" + content + "|" + timestamp);
                }
            } catch (SQLException e) {
                System.err.println("Error sending offline messages: " + e.getMessage());
            }
        }

        private void handleAddContact(String contactEmail) {
            if (userEmail == null) {
                writer.println("CONTACT_FAILED|Not logged in");
                return;
            }

            try {
                // Vérifier si l'utilisateur existe
                PreparedStatement checkStmt = dbConnection.prepareStatement(
                        "SELECT email FROM users WHERE email = ?"
                );
                checkStmt.setString(1, contactEmail);
                ResultSet rs = checkStmt.executeQuery();

                if (rs.next()) {
                    // Ajouter le contact
                    PreparedStatement stmt = dbConnection.prepareStatement(
                            "INSERT OR IGNORE INTO contacts (user_email, contact_email) VALUES (?, ?)"
                    );
                    stmt.setString(1, userEmail);
                    stmt.setString(2, contactEmail);
                    stmt.execute();
                    writer.println("CONTACT_ADDED|" + contactEmail);
                    System.out.println("Contact added: " + userEmail + " -> " + contactEmail);
                } else {
                    writer.println("CONTACT_FAILED|User does not exist");
                }
            } catch (SQLException e) {
                writer.println("CONTACT_FAILED|" + e.getMessage());
                System.err.println("Add contact failed: " + e.getMessage());
            }
        }

        private void handleRemoveContact(String contactEmail) {
            if (userEmail == null) {
                writer.println("CONTACT_FAILED|Not logged in");
                return;
            }

            try {
                PreparedStatement stmt = dbConnection.prepareStatement(
                        "DELETE FROM contacts WHERE user_email = ? AND contact_email = ?"
                );
                stmt.setString(1, userEmail);
                stmt.setString(2, contactEmail);
                int count = stmt.executeUpdate();

                if (count > 0) {
                    writer.println("CONTACT_REMOVED|" + contactEmail);
                    System.out.println("Contact removed: " + userEmail + " -> " + contactEmail);
                } else {
                    writer.println("CONTACT_FAILED|Contact not found");
                }
            } catch (SQLException e) {
                writer.println("CONTACT_FAILED|" + e.getMessage());
                System.err.println("Remove contact failed: " + e.getMessage());
            }
        }

        private void handleGetContacts() {
            if (userEmail == null) {
                writer.println("CONTACT_FAILED|Not logged in");
                return;
            }

            try {
                PreparedStatement stmt = dbConnection.prepareStatement(
                        "SELECT c.contact_email, u.status FROM contacts c " +
                                "JOIN users u ON c.contact_email = u.email " +
                                "WHERE c.user_email = ? " +
                                "ORDER BY c.timestamp DESC"
                );
                stmt.setString(1, userEmail);
                ResultSet rs = stmt.executeQuery();

                StringBuilder contactsList = new StringBuilder();
                while (rs.next()) {
                    String email = rs.getString("contact_email");
                    String status = rs.getString("status");
                    contactsList.append(email).append("|").append(status).append(",");
                }

                String contacts = contactsList.length() > 0 ?
                        contactsList.substring(0, contactsList.length() - 1) : "";
                writer.println("CONTACTS_LIST|" + contacts);
            } catch (SQLException e) {
                writer.println("CONTACT_FAILED|" + e.getMessage());
                System.err.println("Get contacts failed: " + e.getMessage());
            }
        }

        private void handleCreateGroup(String groupName) {
            if (userEmail == null) {
                writer.println("GROUP_FAILED|Not logged in");
                return;
            }

            try {
                PreparedStatement stmt = dbConnection.prepareStatement(
                        "INSERT INTO groups (name, creator) VALUES (?, ?)",
                        Statement.RETURN_GENERATED_KEYS
                );
                stmt.setString(1, groupName);
                stmt.setString(2, userEmail);
                stmt.executeUpdate();

                ResultSet rs = stmt.getGeneratedKeys();
                if (rs.next()) {
                    int groupId = rs.getInt(1);

                    // Ajouter le créateur comme membre
                    PreparedStatement addCreator = dbConnection.prepareStatement(
                            "INSERT INTO group_members (group_id, member_email) VALUES (?, ?)"
                    );
                    addCreator.setInt(1, groupId);
                    addCreator.setString(2, userEmail);
                    addCreator.executeUpdate();

                    writer.println("GROUP_CREATED|" + groupId + "|" + groupName);
                    System.out.println("Group created: " + groupName + " by " + userEmail);
                }
            } catch (SQLException e) {
                writer.println("GROUP_FAILED|" + e.getMessage());
                System.err.println("Create group failed: " + e.getMessage());
            }
        }

        private void handleAddMember(String groupId, String memberEmail) {
            if (userEmail == null) {
                writer.println("GROUP_FAILED|Not logged in");
                return;
            }

            try {
                // Vérifier si l'utilisateur est le créateur du groupe
                PreparedStatement checkCreator = dbConnection.prepareStatement(
                        "SELECT creator FROM groups WHERE group_id = ?"
                );
                checkCreator.setInt(1, Integer.parseInt(groupId));
                ResultSet creatorRs = checkCreator.executeQuery();

                if (creatorRs.next() && creatorRs.getString("creator").equals(userEmail)) {
                    // Vérifier si l'utilisateur à ajouter existe
                    PreparedStatement checkMember = dbConnection.prepareStatement(
                            "SELECT email FROM users WHERE email = ?"
                    );
                    checkMember.setString(1, memberEmail);
                    ResultSet memberRs = checkMember.executeQuery();

                    if (memberRs.next()) {
                        PreparedStatement stmt = dbConnection.prepareStatement(
                                "INSERT OR IGNORE INTO group_members (group_id, member_email) VALUES (?, ?)"
                        );
                        stmt.setInt(1, Integer.parseInt(groupId));
                        stmt.setString(2, memberEmail);
                        stmt.executeUpdate();

                        writer.println("GROUP_MEMBER_ADDED|" + groupId + "|" + memberEmail);
                        System.out.println("Member added to group: " + memberEmail + " to " + groupId);

                        // Informer le membre ajouté s'il est en ligne
                        ClientHandler memberHandler = clients.get(memberEmail);
                        if (memberHandler != null) {
                            PreparedStatement getGroupName = dbConnection.prepareStatement(
                                    "SELECT name FROM groups WHERE group_id = ?"
                            );
                            getGroupName.setInt(1, Integer.parseInt(groupId));
                            ResultSet groupRs = getGroupName.executeQuery();

                            if (groupRs.next()) {
                                String groupName = groupRs.getString("name");
                                memberHandler.writer.println("ADDED_TO_GROUP|" + groupId + "|" + groupName);
                            }
                        }
                    } else {
                        writer.println("GROUP_FAILED|User does not exist");
                    }
                } else {
                    writer.println("GROUP_FAILED|Not authorized to add members");
                }
            } catch (SQLException | NumberFormatException e) {
                writer.println("GROUP_FAILED|" + e.getMessage());
                System.err.println("Add member failed: " + e.getMessage());
            }
        }

        private void handleRemoveMember(String groupId, String memberEmail) {
            if (userEmail == null) {
                writer.println("GROUP_FAILED|Not logged in");
                return;
            }

            try {
                // Vérifier si l'utilisateur est le créateur du groupe
                PreparedStatement checkCreator = dbConnection.prepareStatement(
                        "SELECT creator FROM groups WHERE group_id = ?"
                );
                checkCreator.setInt(1, Integer.parseInt(groupId));
                ResultSet creatorRs = checkCreator.executeQuery();

                if (creatorRs.next() && creatorRs.getString("creator").equals(userEmail) || memberEmail.equals(userEmail)) {
                    PreparedStatement stmt = dbConnection.prepareStatement(
                            "DELETE FROM group_members WHERE group_id = ? AND member_email = ?"
                    );
                    stmt.setInt(1, Integer.parseInt(groupId));
                    stmt.setString(2, memberEmail);
                    int count = stmt.executeUpdate();

                    if (count > 0) {
                        writer.println("GROUP_MEMBER_REMOVED|" + groupId + "|" + memberEmail);
                        System.out.println("Member removed from group: " + memberEmail + " from " + groupId);

                        // Informer le membre retiré s'il est en ligne et si ce n'est pas lui-même qui quitte
                        if (!memberEmail.equals(userEmail)) {
                            ClientHandler memberHandler = clients.get(memberEmail);
                            if (memberHandler != null) {
                                memberHandler.writer.println("REMOVED_FROM_GROUP|" + groupId);
                            }
                        }
                    } else {
                        writer.println("GROUP_FAILED|Member not found in group");
                    }
                } else {
                    writer.println("GROUP_FAILED|Not authorized to remove members");
                }
            } catch (SQLException | NumberFormatException e) {
                writer.println("GROUP_FAILED|" + e.getMessage());
                System.err.println("Remove member failed: " + e.getMessage());
            }
        }

        private void handleGetGroups() {
            if (userEmail == null) {
                writer.println("GROUP_FAILED|Not logged in");
                return;
            }

            try {
                PreparedStatement stmt = dbConnection.prepareStatement(
                        "SELECT g.group_id, g.name, g.creator " +
                                "FROM groups g " +
                                "JOIN group_members gm ON g.group_id = gm.group_id " +
                                "WHERE gm.member_email = ?"
                );
                stmt.setString(1, userEmail);
                ResultSet rs = stmt.executeQuery();

                StringBuilder groupsList = new StringBuilder("GROUPS_LIST");
                while (rs.next()) {
                    int groupId = rs.getInt("group_id");
                    String name = rs.getString("name");
                    String creator = rs.getString("creator");
                    groupsList.append("|").append(groupId).append(",").append(name).append(",").append(creator);
                }

                writer.println(groupsList.toString());
            } catch (SQLException e) {
                writer.println("GROUP_FAILED|" + e.getMessage());
                System.err.println("Get groups failed: " + e.getMessage());
            }
        }

        private void handleGetGroupMembers(String groupId) {
            if (userEmail == null) {
                writer.println("GROUP_FAILED|Not logged in");
                return;
            }

            try {
                // Vérifier si l'utilisateur est membre du groupe
                PreparedStatement checkMember = dbConnection.prepareStatement(
                        "SELECT member_email FROM group_members WHERE group_id = ? AND member_email = ?"
                );
                checkMember.setInt(1, Integer.parseInt(groupId));
                checkMember.setString(2, userEmail);
                ResultSet memberRs = checkMember.executeQuery();

                if (memberRs.next()) {
                    PreparedStatement stmt = dbConnection.prepareStatement(
                            "SELECT gm.member_email, u.status FROM group_members gm " +
                                    "JOIN users u ON gm.member_email = u.email " +
                                    "WHERE gm.group_id = ?"
                    );
                    stmt.setInt(1, Integer.parseInt(groupId));
                    ResultSet rs = stmt.executeQuery();

                    StringBuilder membersList = new StringBuilder("GROUP_MEMBERS|" + groupId);
                    while (rs.next()) {
                        String email = rs.getString("member_email");
                        String status = rs.getString("status");
                        membersList.append("|").append(email).append(",").append(status);
                    }

                    writer.println(membersList.toString());
                } else {
                    writer.println("GROUP_FAILED|Not a member of this group");
                }
            } catch (SQLException | NumberFormatException e) {
                writer.println("GROUP_FAILED|" + e.getMessage());
                System.err.println("Get group members failed: " + e.getMessage());
            }
        }

        private void handleGroupMessage(String groupId, String content) {
            if (userEmail == null) {
                writer.println("GROUP_FAILED|Not logged in");
                return;
            }

            try {
                // Verify if the user is a member of the group
                PreparedStatement checkMember = dbConnection.prepareStatement(
                        "SELECT member_email FROM group_members WHERE group_id = ? AND member_email = ?"
                );
                checkMember.setInt(1, Integer.parseInt(groupId));
                checkMember.setString(2, userEmail);
                ResultSet memberRs = checkMember.executeQuery();

                if (memberRs.next()) {
                    // Insert the message into the database
                    PreparedStatement insertMsg = dbConnection.prepareStatement(
                            "INSERT INTO messages (sender, receiver, content, timestamp) " +
                                    "VALUES (?, ?, ?, CURRENT_TIMESTAMP)"
                    );
                    insertMsg.setString(1, userEmail);
                    insertMsg.setString(2, "GROUP:" + groupId);
                    insertMsg.setString(3, content);
                    insertMsg.executeUpdate();

                    // Retrieve all group members
                    PreparedStatement getMembers = dbConnection.prepareStatement(
                            "SELECT member_email FROM group_members WHERE group_id = ? AND member_email != ?"
                    );
                    getMembers.setInt(1, Integer.parseInt(groupId));
                    getMembers.setString(2, userEmail);
                    ResultSet membersRs = getMembers.executeQuery();

                    // Notify all online members
                    while (membersRs.next()) {
                        String memberEmail = membersRs.getString("member_email");
                        ClientHandler memberHandler = clients.get(memberEmail);
                        if (memberHandler != null) {
                            memberHandler.writer.println("GROUP_MESSAGE|" + groupId + "|" + userEmail + "|" + content);
                        }
                    }

                    // Confirm to the sender
                    writer.println("GROUP_MESSAGE_SENT|" + groupId);
                    System.out.println("Group message sent by " + userEmail + " to group " + groupId);
                } else {
                    writer.println("GROUP_FAILED|Not a member of this group");
                }
            } catch (SQLException | NumberFormatException e) {
                writer.println("GROUP_FAILED|" + e.getMessage());
                System.err.println("Group message failed: " + e.getMessage());
            }
        }

        private void handleGetGroupMessages(String groupId) {
            if (userEmail == null) {
                writer.println("GROUP_FAILED|Not logged in");
                return;
            }

            try {
                PreparedStatement checkMember = dbConnection.prepareStatement(
                        "SELECT member_email FROM group_members WHERE group_id = ? AND member_email = ?"
                );
                checkMember.setInt(1, Integer.parseInt(groupId));
                checkMember.setString(2, userEmail);
                ResultSet memberRs = checkMember.executeQuery();

                if (memberRs.next()) {
                    PreparedStatement getMessages = dbConnection.prepareStatement(
                            "SELECT sender, content, timestamp, file_data, file_name, file_type FROM messages " +
                                    "WHERE receiver = ? ORDER BY timestamp"
                    );
                    getMessages.setString(1, "GROUP:" + groupId);
                    ResultSet messagesRs = getMessages.executeQuery();

                    StringBuilder messagesList = new StringBuilder("GROUP_MESSAGES|" + groupId);
                    while (messagesRs.next()) {
                        String sender = messagesRs.getString("sender");
                        String content = messagesRs.getString("content");
                        String timestamp = messagesRs.getString("timestamp");
                        String fileData = messagesRs.getString("file_data");
                        String fileName = messagesRs.getString("file_name");
                        String fileType = messagesRs.getString("file_type");

                        messagesList.append("|").append(sender).append(",")
                                .append(content).append(",")
                                .append(timestamp);

                        if (fileData != null && fileName != null && fileType != null) {
                            messagesList.append(",").append(fileName).append(",")
                                    .append(fileType).append(",")
                                    .append(fileData);
                        }
                    }
                    writer.println(messagesList.toString());
                } else {
                    writer.println("GROUP_FAILED|Not a member of this group");
                }
            } catch (SQLException | NumberFormatException e) {
                writer.println("GROUP_FAILED|" + e.getMessage());
                System.err.println("Get group messages failed: " + e.getMessage());
            }
        }
    }
}