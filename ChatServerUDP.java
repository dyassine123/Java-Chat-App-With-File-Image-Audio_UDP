import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ChatServerUDP {
    private static final int PORT = 5000;
    private static final Map<String, InetSocketAddress> clientAddresses = Collections.synchronizedMap(new HashMap<>());
    private static DatagramSocket serverSocket;

    private static final ExecutorService executor = Executors.newFixedThreadPool(10);

    public static void main(String[] args) {
        System.out.println("✅ Serveur chat UDP + image + vocal + fichier démarré sur le port " + PORT);

        try {
            serverSocket = new DatagramSocket(PORT);
            byte[] receiveBuffer = new byte[65507];

            while (true) {
                DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                serverSocket.receive(receivePacket);

                byte[] packetData = Arrays.copyOf(receivePacket.getData(), receivePacket.getLength());
                executor.execute(new PacketHandler(packetData, receivePacket.getAddress(), receivePacket.getPort()));
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (serverSocket != null) serverSocket.close();
            executor.shutdown();
        }
    }

    private static class PacketHandler implements Runnable {
        private byte[] data;
        private InetAddress clientAddress;
        private int clientPort;

        public PacketHandler(byte[] data, InetAddress clientAddress, int clientPort) {
            this.data = data;
            this.clientAddress = clientAddress;
            this.clientPort = clientPort;
        }

        public void run() {
            try {
                ByteArrayInputStream bais = new ByteArrayInputStream(data);
                DataInputStream in = new DataInputStream(bais);

                String type = in.readUTF();

                if (type.equals("CONNECT")) {
                    handleConnect(in);
                } else if (type.equals("TEXT")) {
                    handleText(in);
                } else if (type.equals("IMG")) {
                    handleBinaryData(in, "IMG");
                } else if (type.equals("AUDIO")) {
                    handleBinaryData(in, "AUDIO");
                } else if (type.equals("FILE")) {
                    handleBinaryData(in, "FILE");
                } else if (type.equals("DISCONNECT")) {
                    handleDisconnect(in);
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void handleConnect(DataInputStream in) throws IOException {
            String name = in.readUTF();
            InetSocketAddress clientAddress = new InetSocketAddress(this.clientAddress, this.clientPort);
            clientAddresses.put(name, clientAddress);

            System.out.println("🟢 " + name + " connecté depuis " + clientAddress);
            broadcast("🟢 " + name + " a rejoint le chat !", name);
            sendClientList();
        }

        private void handleText(DataInputStream in) throws IOException {
            String name = in.readUTF();
            String dest = in.readUTF();
            String timestamp = in.readUTF();
            String msg = in.readUTF();

            String formattedMsg = "[" + timestamp + "] " + name + " : " + msg;

            if (dest.equalsIgnoreCase("TOUS")) {
                broadcast(formattedMsg, name);
            } else {
                sendPrivate(dest, "[" + timestamp + "] (privé de " + name + ") : " + msg);
            }
        }

        private void handleBinaryData(DataInputStream in, String dataType) throws IOException {
            String name = in.readUTF();
            String dest = in.readUTF();
            String filename = in.readUTF();
            int size = in.readInt();
            byte[] data = new byte[size];
            in.readFully(data);

            if (dest.equalsIgnoreCase("TOUS")) {
                broadcastBinary(dataType, name, filename, data, name);
            } else {
                sendPrivateBinary(dest, dataType, name, filename, data);
            }
        }

        private void handleDisconnect(DataInputStream in) throws IOException {
            String name = in.readUTF();
            if (clientAddresses.remove(name) != null) {
                System.out.println("🔴 " + name + " déconnecté");
                broadcast("🔴 " + name + " a quitté le chat !", name);
                sendClientList();
            }
        }

        private void broadcast(String msg, String excludeSender) {
            List<String> disconnectedClients = new ArrayList<>();

            synchronized (clientAddresses) {
                for (Map.Entry<String, InetSocketAddress> entry : clientAddresses.entrySet()) {
                    if (!entry.getKey().equals(excludeSender)) {
                        boolean success = sendText(entry.getValue(), "TEXT", "ALL", msg);
                        if (!success) {
                            disconnectedClients.add(entry.getKey());
                        }
                    }
                }
            }

            // Retirer les clients déconnectés
            for (String client : disconnectedClients) {
                clientAddresses.remove(client);
                System.out.println("🔴 Client " + client + " retiré (déconnexion détectée)");
            }

            if (!disconnectedClients.isEmpty()) {
                sendClientList();
            }
        }

        private void sendPrivate(String to, String msg) {
            InetSocketAddress address = clientAddresses.get(to);
            if (address != null) {
                boolean success = sendText(address, "TEXT", "PRIVATE", msg);
                if (!success) {
                    clientAddresses.remove(to);
                    System.out.println("🔴 Client " + to + " retiré (déconnexion détectée)");
                    sendClientList();
                }
            }
        }

        private void broadcastBinary(String type, String sender, String filename, byte[] data, String excludeSender) {
            List<String> disconnectedClients = new ArrayList<>();

            synchronized (clientAddresses) {
                for (Map.Entry<String, InetSocketAddress> entry : clientAddresses.entrySet()) {
                    if (!entry.getKey().equals(excludeSender)) {
                        boolean success = sendBinary(entry.getValue(), type, sender, filename, data);
                        if (!success) {
                            disconnectedClients.add(entry.getKey());
                        }
                    }
                }
            }

            // Retirer les clients déconnectés
            for (String client : disconnectedClients) {
                clientAddresses.remove(client);
                System.out.println("🔴 Client " + client + " retiré (déconnexion détectée)");
            }

            if (!disconnectedClients.isEmpty()) {
                sendClientList();
            }
        }

        private void sendPrivateBinary(String to, String type, String sender, String filename, byte[] data) {
            InetSocketAddress address = clientAddresses.get(to);
            if (address != null) {
                boolean success = sendBinary(address, type, sender, filename, data);
                if (!success) {
                    clientAddresses.remove(to);
                    System.out.println("🔴 Client " + to + " retiré (déconnexion détectée)");
                    sendClientList();
                }
            }
        }

        private boolean sendText(InetSocketAddress address, String type, String target, String msg) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(baos);

                out.writeUTF(type);
                out.writeUTF(target);
                out.writeUTF(msg);
                out.flush();

                byte[] data = baos.toByteArray();
                DatagramPacket packet = new DatagramPacket(data, data.length, address.getAddress(), address.getPort());
                serverSocket.send(packet);
                return true;

            } catch (IOException e) {
                System.out.println("❌ Erreur envoi à " + address + ": " + e.getMessage());
                return false;
            }
        }

        private boolean sendBinary(InetSocketAddress address, String type, String sender, String filename, byte[] fileData) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(baos);

                out.writeUTF(type);
                out.writeUTF(sender);
                out.writeUTF(filename);
                out.writeInt(fileData.length);
                out.write(fileData);
                out.flush();

                byte[] data = baos.toByteArray();
                if (data.length > 65507) {
                    System.err.println("⚠️ Fichier trop volumineux pour UDP: " + filename);
                    return true; // On ne considère pas ça comme une déconnexion
                }

                DatagramPacket packet = new DatagramPacket(data, data.length, address.getAddress(), address.getPort());
                serverSocket.send(packet);
                return true;

            } catch (IOException e) {
                System.out.println("❌ Erreur envoi fichier à " + address + ": " + e.getMessage());
                return false;
            }
        }

        private void sendClientList() {
            String listStr = getClientListString();

            List<String> disconnectedClients = new ArrayList<>();

            synchronized (clientAddresses) {
                for (InetSocketAddress address : clientAddresses.values()) {
                    boolean success = sendClientListToAddress(address, listStr);
                    if (!success) {
                        // Trouver le client correspondant à cette adresse
                        for (Map.Entry<String, InetSocketAddress> entry : clientAddresses.entrySet()) {
                            if (entry.getValue().equals(address)) {
                                disconnectedClients.add(entry.getKey());
                                break;
                            }
                        }
                    }
                }
            }

            // Retirer les clients déconnectés
            for (String client : disconnectedClients) {
                clientAddresses.remove(client);
                System.out.println("🔴 Client " + client + " retiré (déconnexion détectée lors de l'envoi de liste)");
            }

            // Renvoyer la liste mise à jour si nécessaire
            if (!disconnectedClients.isEmpty()) {
                sendClientList();
            }
        }

        private String getClientListString() {
            StringBuilder list = new StringBuilder();
            synchronized (clientAddresses) {
                for (String name : clientAddresses.keySet()) {
                    list.append(name).append(",");
                }
            }
            return list.toString();
        }

        private boolean sendClientListToAddress(InetSocketAddress address, String listStr) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(baos);

                out.writeUTF("LISTE");
                out.writeUTF(listStr);
                out.flush();

                byte[] data = baos.toByteArray();
                DatagramPacket packet = new DatagramPacket(data, data.length, address.getAddress(), address.getPort());
                serverSocket.send(packet);
                return true;

            } catch (IOException e) {
                System.out.println("❌ Erreur envoi liste à " + address + ": " + e.getMessage());
                return false;
            }
        }
    }
}