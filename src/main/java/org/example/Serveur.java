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
            if (dbFile.exists()) {
                dbFile.delete();
            }

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
                    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                    status TEXT DEFAULT 'sent',
                    FOREIGN KEY (sender) REFERENCES users(email),
                    FOREIGN KEY (receiver) REFERENCES users(email)
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

            Statement stmt = dbConnection.createStatement();
            stmt.execute(createUsersTable);
            stmt.execute(createMessagesTable);
            stmt.execute(createContactsTable);
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
    }
}