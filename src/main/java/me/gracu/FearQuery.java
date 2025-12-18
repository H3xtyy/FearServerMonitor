package me.gracu;

import java.io.IOException;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class FearQuery {

    private static final byte[] PACKET_DETAILS = {
            (byte) 0xFE, (byte) 0xFD, 0x00, 0x43, 0x4F, 0x52, 0x59, (byte) 0xFF, 0x00, 0x00
    };

    private static final byte[] PACKET_PLAYERS = {
            (byte) 0xFE, (byte) 0xFD, 0x00, 0x43, 0x4F, 0x52, 0x58, 0x00, (byte) 0xFF, (byte) 0xFF
    };

    public static ServerStatus query(String ip, int port, int timeoutMs) {
        ServerStatus status = new ServerStatus();
        long startTime = System.currentTimeMillis();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            InetAddress address = InetAddress.getByName(ip);

            DatagramPacket detailsPacket = new DatagramPacket(
                    PACKET_DETAILS, PACKET_DETAILS.length, address, port
            );
            socket.send(detailsPacket);

            byte[] detailsBuffer = new byte[4096];
            DatagramPacket detailsResponse = new DatagramPacket(detailsBuffer, detailsBuffer.length);

            try {
                socket.receive(detailsResponse);
                status.ping = System.currentTimeMillis() - startTime;

                Map<String, String> details = parseDetailsResponse(
                        detailsResponse.getData(),
                        detailsResponse.getLength()
                );

                status.online = true;
                status.serverName = details.getOrDefault("hostname", "Unknown");
                status.map = details.getOrDefault("mapname", "Unknown");
                status.gameVersion = details.getOrDefault("gamever",
                        details.getOrDefault("version", "Unknown"));
                status.gameType = details.getOrDefault("gametype", "Unknown");

                try {
                    status.maxPlayers = Integer.parseInt(details.getOrDefault("maxplayers", "0"));
                } catch (NumberFormatException e) {
                    status.maxPlayers = 0;
                }

                try {
                    status.currentPlayers = Integer.parseInt(details.getOrDefault("numplayers", "0"));
                } catch (NumberFormatException e) {
                    status.currentPlayers = 0;
                }

                if (status.currentPlayers > 0) {
                    queryPlayers(socket, address, port, timeoutMs, status);
                }

            } catch (SocketTimeoutException e) {
                status.error = "Timeout receiving details";
                status.online = false;
            }

        } catch (IOException e) {
            status.error = e.getMessage();
            status.online = false;
        }

        return status;
    }

    private static void queryPlayers(DatagramSocket socket, InetAddress address, int port,
                                     int timeoutMs, ServerStatus status) throws IOException {
        try {
            socket.setSoTimeout(timeoutMs / 2);

            DatagramPacket playersPacket = new DatagramPacket(
                    PACKET_PLAYERS, PACKET_PLAYERS.length, address, port
            );
            socket.send(playersPacket);

            byte[] playersBuffer = new byte[4096];
            DatagramPacket playersResponse = new DatagramPacket(playersBuffer, playersBuffer.length);
            socket.receive(playersResponse);

            parsePlayersResponse(playersResponse.getData(), playersResponse.getLength(), status);

        } catch (SocketTimeoutException e) {
            System.out.println("Warning: Player query timeout (normal for some servers)");
        } catch (IOException e) {
            System.out.println("Warning: Player query failed: " + e.getMessage());
        }
    }

    private static Map<String, String> parseDetailsResponse(byte[] data, int length) {
        Map<String, String> result = new HashMap<>();

        if (length < 5) return result;

        if (data[0] != 0x00 || data[1] != 0x43 || data[2] != 0x4F ||
                data[3] != 0x52 || data[4] != 0x59) {
            return result;
        }

        int pos = 5;
        Charset iso8859 = Charset.forName("ISO-8859-1");

        while (pos < length) {
            int keyStart = pos;
            while (pos < length && data[pos] != 0x00) {
                pos++;
            }

            if (pos >= length) break;

            String key = new String(data, keyStart, pos - keyStart, iso8859);
            pos++;

            int valueStart = pos;
            while (pos < length && data[pos] != 0x00) {
                pos++;
            }

            if (pos >= length) break;

            String value = new String(data, valueStart, pos - valueStart, iso8859);
            pos++;

            key = convertIsoToUtf8(key);
            value = convertIsoToUtf8(value);

            if (!key.isEmpty()) {
                result.put(key.toLowerCase(), value);
            }

            if (pos < length && data[pos] == 0x00) {
                break;
            }
        }

        return result;
    }

    private static void parsePlayersResponse(byte[] data, int length, ServerStatus status) {
        if (length < 6) return;

        if (data[0] != 0x00 || data[1] != 0x43 || data[2] != 0x4F ||
                data[3] != 0x52 || data[4] != 0x58) {
            return;
        }

        status.playerList = new ArrayList<>();
        int pos = 5;
        Charset iso8859 = Charset.forName("ISO-8859-1");

        try {
            if (pos < length && data[pos] == 0x00) {
                pos++;
            }

            if (pos >= length) return;
            int numPlayers = data[pos] & 0xFF;
            pos++;

            List<String> playerFields = new ArrayList<>();
            while (pos < length && data[pos] != 0x00) {
                int fieldStart = pos;
                while (pos < length && data[pos] != 0x00) {
                    pos++;
                }

                if (pos >= length) break;

                String field = new String(data, fieldStart, pos - fieldStart, iso8859);
                field = convertIsoToUtf8(field);
                playerFields.add(field);
                pos++;
            }

            if (pos < length && data[pos] == 0x00) {
                pos++;
            }

            for (int i = 0; i < numPlayers && pos < length; i++) {
                Map<String, String> playerData = new HashMap<>();

                for (String field : playerFields) {
                    if (pos >= length) break;

                    int valueStart = pos;
                    while (pos < length && data[pos] != 0x00) {
                        pos++;
                    }

                    if (pos >= length) break;

                    String value = new String(data, valueStart, pos - valueStart, iso8859);
                    value = convertIsoToUtf8(value);
                    playerData.put(field, value);
                    pos++;
                }

                if (!playerData.isEmpty()) {
                    status.playerList.add(playerData);
                }
            }

        } catch (Exception e) {
            System.out.println("Warning: Error parsing player data: " + e.getMessage());
        }
    }

    private static String convertIsoToUtf8(String isoString) {
        try {
            byte[] isoBytes = isoString.getBytes("ISO-8859-1");
            return new String(isoBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return isoString;
        }
    }

    public static class ServerStatus {
        public boolean online = false;
        public String serverName = "";
        public String map = "";
        public String gameVersion = "";
        public int maxPlayers = 0;
        public int currentPlayers = 0;
        public String gameType = "";
        public long ping = -1;
        public String error = "";
        public List<Map<String, String>> playerList = new ArrayList<>();

        @Override
        public String toString() {
            if (!online) return "Offline";

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%d/%d players on %s",
                    currentPlayers, maxPlayers, map));

            if (ping > 0) {
                sb.append(String.format(" (ping: %dms)", ping));
            }

            if (!playerList.isEmpty()) {
                sb.append("\nPlayers: ");
                for (Map<String, String> player : playerList) {
                    String name = player.getOrDefault("playername",
                            player.getOrDefault("name", "Unknown"));
                    sb.append(name).append(", ");
                }
                if (sb.length() > 2) {
                    sb.setLength(sb.length() - 2);
                }
            }

            return sb.toString();
        }
    }
}