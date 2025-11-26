import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.Desktop;

public class ChatClientUDP extends JFrame {
    private JTextPane chatPane;
    private JTextField inputField;
    private JButton sendButton, imageButton, voiceButton, fileButton;
    private JComboBox<String> destSelector;
    private DefaultListModel<String> listModel;
    private JList<String> userList;
    private JLabel userLabel;

    private DatagramSocket socket;
    private InetAddress serverAddress;
    private int serverPort;
    private String name;
    private volatile boolean connected = true;

    private boolean recording = false;
    private TargetDataLine microphone;
    private File currentAudioFile;

    public ChatClientUDP(String serverAddress, int port) {
        this.name = JOptionPane.showInputDialog(this, "Entrez votre pseudo :");
        if (this.name == null || this.name.trim().isEmpty()) this.name = "Client" + new Random().nextInt(1000);

        setTitle(this.name + " - Chat UDP (Texte + Image + Vocal + Fichier)");
        setSize(700, 500);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());

        // Interface utilisateur
        userLabel = new JLabel("Connecté en tant que : " + this.name);
        add(userLabel, BorderLayout.NORTH);

        chatPane = new JTextPane();
        chatPane.setEditable(false);
        add(new JScrollPane(chatPane), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        inputField = new JTextField();
        sendButton = new JButton("Envoyer");
        imageButton = new JButton("📸 Image");
        voiceButton = new JButton("🎙️ Vocal");
        fileButton = new JButton("📁 Fichier");

        JPanel topBottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        destSelector = new JComboBox<>();
        destSelector.addItem("TOUS");
        topBottom.add(new JLabel("À :"));
        topBottom.add(destSelector);
        topBottom.add(imageButton);
        topBottom.add(voiceButton);
        topBottom.add(fileButton);
        bottom.add(topBottom, BorderLayout.NORTH);

        JPanel msgPanel = new JPanel(new BorderLayout());
        msgPanel.add(inputField, BorderLayout.CENTER);
        msgPanel.add(sendButton, BorderLayout.EAST);
        bottom.add(msgPanel, BorderLayout.SOUTH);

        add(bottom, BorderLayout.SOUTH);

        listModel = new DefaultListModel<>();
        userList = new JList<>(listModel);
        userList.setBorder(BorderFactory.createTitledBorder("Utilisateurs Connectés")); // Titre modifié
        add(new JScrollPane(userList), BorderLayout.EAST);

        try {
            this.serverAddress = InetAddress.getByName(serverAddress);
            this.serverPort = port;
            this.socket = new DatagramSocket();

            sendConnect();
            new Thread(this::listenServer).start();

            appendText("🟢 Connecté au serveur " + serverAddress + ":" + port + "\n", Color.GREEN);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Connexion impossible au serveur: " + e.getMessage(), "Erreur", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        sendButton.addActionListener(e -> sendMessage());
        inputField.addActionListener(e -> sendMessage());
        imageButton.addActionListener(e -> sendImage());
        voiceButton.addActionListener(e -> toggleVoiceRecording());
        fileButton.addActionListener(e -> sendFile());

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent windowEvent) {
                disconnect();
                dispose();
                System.exit(0);
            }
        });

        setVisible(true);
    }

    private void sendConnect() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);

            out.writeUTF("CONNECT");
            out.writeUTF(name);
            out.flush();

            byte[] data = baos.toByteArray();
            DatagramPacket packet = new DatagramPacket(data, data.length, serverAddress, serverPort);
            socket.send(packet);

        } catch (IOException e) {
            appendText("❌ Erreur de connexion: " + e.getMessage() + "\n", Color.RED);
        }
    }



    private void disconnect() {
        if (!connected) return;

        connected = false;
        appendText("🔴 Déconnexion en cours...\n", Color.ORANGE);

        sendDisconnect();

        if (socket != null && !socket.isClosed()) {
            socket.close();
        }

        if (recording && microphone != null) {
            microphone.stop();
            microphone.close();
        }

        appendText("🔴 Déconnecté du serveur\n", Color.RED);
    }
    private void sendDisconnect() {
        if (!connected) return;

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);

            out.writeUTF("DISCONNECT");
            out.writeUTF(name);
            out.flush();

            byte[] data = baos.toByteArray();
            DatagramPacket packet = new DatagramPacket(data, data.length, serverAddress, serverPort);
            socket.send(packet);

        } catch (IOException e) {
            // Ignorer les erreurs lors de la déconnexion
        }
    }

    private String getTimestamp() {
        return new java.text.SimpleDateFormat("HH:mm:ss").format(new Date());
    }

    private void sendMessage() {
        if (!connected) {
            appendText("⚠️ Non connecté au serveur\n", Color.RED);
            return;
        }

        String msg = inputField.getText().trim();
        if (msg.isEmpty()) return;
        String dest = (String) destSelector.getSelectedItem();
        if (dest == null) dest = "TOUS";

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);

            out.writeUTF("TEXT");
            out.writeUTF(name);
            out.writeUTF(dest);
            out.writeUTF(getTimestamp());
            out.writeUTF(msg);
            out.flush();

            byte[] data = baos.toByteArray();
            DatagramPacket packet = new DatagramPacket(data, data.length, serverAddress, serverPort);
            socket.send(packet);

            appendText("[" + getTimestamp() + "] Moi -> " + (dest.equals("TOUS") ? "Tous" : dest) + " : " + msg + "\n", Color.BLUE);
            inputField.setText("");
        } catch (IOException e) {
            appendText("⚠️ Erreur d'envoi: " + e.getMessage() + "\n", Color.RED);
        }
    }

    private void sendImage() {
        if (!connected) {
            appendText("⚠️ Non connecté au serveur\n", Color.RED);
            return;
        }

        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            sendBinaryFile("IMG", file);
        }
    }

    private void sendFile() {
        if (!connected) {
            appendText("⚠️ Non connecté au serveur\n", Color.RED);
            return;
        }

        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            sendBinaryFile("FILE", file);
        }
    }

    private void sendBinaryFile(String type, File file) {
        String dest = (String) destSelector.getSelectedItem();
        if (dest == null) dest = "TOUS";

        try {
            byte[] data = java.nio.file.Files.readAllBytes(file.toPath());

            if (data.length > 60000) {
                JOptionPane.showMessageDialog(this, "Fichier trop volumineux pour UDP (max ~60KB)", "Erreur", JOptionPane.ERROR_MESSAGE);
                return;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);

            out.writeUTF(type);
            out.writeUTF(name);
            out.writeUTF(dest);
            out.writeUTF(file.getName());
            out.writeInt(data.length);
            out.write(data);
            out.flush();

            byte[] packetData = baos.toByteArray();
            DatagramPacket packet = new DatagramPacket(packetData, packetData.length, serverAddress, serverPort);
            socket.send(packet);

            appendText("[" + getTimestamp() + "] Moi -> " + dest + " : " + file.getName() + "\n", Color.BLUE);
        } catch (IOException e) {
            appendText("⚠️ Erreur d'envoi du fichier: " + e.getMessage() + "\n", Color.RED);
        }
    }

    private void toggleVoiceRecording() {
        if (!connected) {
            appendText("⚠️ Non connecté au serveur\n", Color.RED);
            return;
        }

        if (!recording) startRecording();
        else stopRecordingAndSend();
    }

    private void startRecording() {
        try {
            AudioFormat format = new AudioFormat(16000, 16, 2, true, true);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            microphone = (TargetDataLine) AudioSystem.getLine(info);
            microphone.open(format);
            microphone.start();

            currentAudioFile = new File("voice_" + System.currentTimeMillis() + ".wav");
            new Thread(() -> {
                try (AudioInputStream ais = new AudioInputStream(microphone)) {
                    AudioSystem.write(ais, AudioFileFormat.Type.WAVE, currentAudioFile);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }).start();

            recording = true;
            voiceButton.setText("⏹️ Stop");
            appendText("🎙️ Enregistrement...\n", Color.GRAY);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopRecordingAndSend() {
        try {
            microphone.stop();
            microphone.close();
            recording = false;
            voiceButton.setText("🎙️ Vocal");
            appendText("🎤 Envoi du vocal...\n", Color.GRAY);

            String dest = (String) destSelector.getSelectedItem();
            if (dest == null) dest = "TOUS";

            byte[] data = java.nio.file.Files.readAllBytes(currentAudioFile.toPath());

            if (data.length > 60000) {
                JOptionPane.showMessageDialog(this, "Audio trop long pour UDP", "Erreur", JOptionPane.ERROR_MESSAGE);
                return;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);

            out.writeUTF("AUDIO");
            out.writeUTF(name);
            out.writeUTF(dest);
            out.writeUTF(currentAudioFile.getName());
            out.writeInt(data.length);
            out.write(data);
            out.flush();

            byte[] packetData = baos.toByteArray();
            DatagramPacket packet = new DatagramPacket(packetData, packetData.length, serverAddress, serverPort);
            socket.send(packet);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void listenServer() {
        byte[] buffer = new byte[65507];

        while (connected) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                ByteArrayInputStream bais = new ByteArrayInputStream(packet.getData(), 0, packet.getLength());
                DataInputStream in = new DataInputStream(bais);

                String type = in.readUTF();

                if (type.equals("TEXT")) {
                    String target = in.readUTF();
                    String msg = in.readUTF();
                    appendText(msg + "\n", target.equals("ALL") ? Color.BLACK : Color.MAGENTA);
                } else if (type.equals("IMG")) {
                    receiveBinaryData(in, "IMG");
                } else if (type.equals("AUDIO")) {
                    receiveBinaryData(in, "AUDIO");
                } else if (type.equals("FILE")) {
                    receiveBinaryData(in, "FILE");
                } else if (type.equals("LISTE")) {
                    String listStr = in.readUTF();
                    updateUserList(listStr);
                }

            } catch (SocketException e) {
                if (connected) {
                    appendText("🔴 Déconnecté du serveur\n", Color.RED);
                }
                break;
            } catch (IOException e) {
                if (connected) {
                    appendText("⚠️ Erreur de connexion: " + e.getMessage() + "\n", Color.RED);
                }
                break;
            }
        }
    }

    private void receiveBinaryData(DataInputStream in, String type) throws IOException {
        String sender = in.readUTF();
        String filename = in.readUTF();
        int size = in.readInt();
        byte[] data = new byte[size];
        in.readFully(data);

        File file = new File("received_" + filename);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }

        if (type.equals("IMG")) appendImage(file);
        else if (type.equals("AUDIO")) appendAudioMessage(sender, file);
        else if (type.equals("FILE")) appendFileMessage(sender, file);
    }

    private void appendText(String msg, Color color) {
        SwingUtilities.invokeLater(() -> {
            StyledDocument doc = chatPane.getStyledDocument();
            Style style = chatPane.addStyle("Style", null);
            StyleConstants.setForeground(style, color);
            try {
                doc.insertString(doc.getLength(), msg, style);
                chatPane.setCaretPosition(doc.getLength());
            } catch (BadLocationException e) {
                e.printStackTrace();
            }
        });
    }

    private void appendImage(File file) {
        try {
            ImageIcon icon = new ImageIcon(ImageIO.read(file));
            SwingUtilities.invokeLater(() -> {
                chatPane.setCaretPosition(chatPane.getDocument().getLength());
                chatPane.insertIcon(icon);
                appendText("\n", Color.BLACK);
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void appendAudioMessage(String sender, File audioFile) {
        SwingUtilities.invokeLater(() -> {
            JButton playBtn = new JButton("▶️ Écouter " + sender);
            playBtn.addActionListener(e -> playAudio(audioFile));
            chatPane.insertComponent(playBtn);
            appendText("\n", Color.BLACK);
        });
    }

    private void appendFileMessage(String sender, File file) {
        SwingUtilities.invokeLater(() -> {
            JButton openBtn = new JButton("📂 Ouvrir " + file.getName() + " (" + sender + ")");
            openBtn.addActionListener(e -> openFile(file));
            chatPane.insertComponent(openBtn);
            appendText("\n", Color.BLACK);
        });
    }

    private void playAudio(File file) {
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(file);
            Clip clip = AudioSystem.getClip();
            clip.open(ais);
            clip.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void openFile(File file) {
        try {
            Desktop.getDesktop().open(file);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Impossible d'ouvrir le fichier.", "Erreur", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateUserList(String listStr) {
        SwingUtilities.invokeLater(() -> {
            listModel.clear();
            destSelector.removeAllItems();
            destSelector.addItem("TOUS");

            if (listStr != null && !listStr.isEmpty()) {
                String[] names = listStr.split(",");
                for (String n : names) {
                    if (!n.isEmpty() && !n.equals(name)) {
                        listModel.addElement(n);
                        destSelector.addItem(n);
                    }
                }
            }

            // Mettre à jour le titre avec le nombre d'utilisateurs connectés
            int userCount = listModel.size();
            userList.setBorder(BorderFactory.createTitledBorder(
                    "Utilisateurs  (" + userCount + ")"
            ));
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new ChatClientUDP("localhost", 5000);
        });
    }
}