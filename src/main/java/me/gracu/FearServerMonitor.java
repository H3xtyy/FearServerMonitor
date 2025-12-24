package me.gracu;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FearServerMonitor {

    private static final String CONFIG_FILE = "config.properties";
    private static final String SERVER_LIST_FILE = "fear_server_list.json";

    private static String token;
    private static long channelId;
    private static int listCheckInterval;
    private static int serversCheckInterval;
    private static int playerThreshold;

    private static List<FearServerListFetcher.FearServer> serverList = new ArrayList<>();

    private static class ServerMessageInfo {
        long messageId;
        String lastContent;
        String lastMap;
        int lastPlayerCount;

        ServerMessageInfo(long messageId, String lastContent, String lastMap, int lastPlayerCount) {
            this.messageId = messageId;
            this.lastContent = lastContent;
            this.lastMap = lastMap;
            this.lastPlayerCount = lastPlayerCount;
        }
    }

    private static final Map<String, ServerMessageInfo> serverMessages = new ConcurrentHashMap<>();

    private static JDA jda;
    private static TextChannel targetChannel;
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public static void main(String[] args) {
        try {
            loadConfiguration();

            jda = JDABuilder.createDefault(token)
                    .setActivity(Activity.watching("F.E.A.R."))
                    .build();

            jda.awaitReady();
            System.out.println("The bot has been launched!");

            targetChannel = jda.getTextChannelById(channelId);
            if (targetChannel == null) {
                System.err.println("No channel found with ID: " + channelId);
                System.exit(1);
            }

            loadServerList();
            scheduleTasks();

        } catch (Exception e) {
            System.err.println("Error while starting the bot: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void loadConfiguration() throws IOException {
        Properties props = new Properties();
        File configFile = new File(CONFIG_FILE);

        if (!configFile.exists()) {
            props.setProperty("token", "YOUR_BOT_TOKEN_HERE");
            props.setProperty("channel_id", "YOUR_CHANNEL_ID_HERE");
            props.setProperty("list_check_interval", "24"); // hours
            props.setProperty("servers_check_interval", "2"); // minutes
            props.setProperty("player_threshold", "3");

            try (FileOutputStream out = new FileOutputStream(CONFIG_FILE)) {
                props.store(out, "Configuration for Fear Server Monitor Bot");
                System.out.println("Configuration file created: " + CONFIG_FILE);
                System.out.println("Fill it in with the correct information!");
            }
            System.exit(0);
        }

        try (FileInputStream in = new FileInputStream(CONFIG_FILE)) {
            props.load(in);
        }

        token = props.getProperty("token");
        channelId = Long.parseLong(props.getProperty("channel_id"));
        listCheckInterval = Integer.parseInt(props.getProperty("list_check_interval"));
        serversCheckInterval = Integer.parseInt(props.getProperty("servers_check_interval"));
        playerThreshold = Integer.parseInt(props.getProperty("player_threshold"));

        System.out.println("Config loaded!");
    }

    private static void loadServerList() {
        File serverFile = new File(SERVER_LIST_FILE);
        if (!serverFile.exists()) {
            System.out.println("The server list file does not exist. Downloading...");
            updateServerList();
            return;
        }

        try (Reader reader = new FileReader(SERVER_LIST_FILE)) {
            Gson gson = new Gson();
            Type listType = new TypeToken<List<FearServerListFetcher.FearServer>>(){}.getType();
            serverList = gson.fromJson(reader, listType);
            System.out.println("Loaded " + serverList.size() + " servers from file.");
        } catch (IOException e) {
            System.err.println("Error loading server list: " + e.getMessage());
            serverList = new ArrayList<>();
        }
    }

    private static void updateServerList() {
        System.out.println("Updating the server list...");
        try {
            boolean success = FearServerListFetcher.fetchAndSaveServerList();
            if (success) {
                loadServerList();
                System.out.println("Server list updated. Loaded " + serverList.size() + " servers.");

            } else {
                System.err.println("Failed to update the server list.");
            }
        } catch (Exception e) {
            System.err.println("Error updating server list: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void scheduleTasks() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                updateServerList();
            } catch (Exception e) {
                System.err.println("Error in scheduled task to update list: " + e.getMessage());
            }
        }, 0, listCheckInterval, TimeUnit.HOURS);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkAllServers();
            } catch (Exception e) {
                System.err.println("Error in scheduled task for checking servers: " + e.getMessage());
            }
        }, 1, serversCheckInterval, TimeUnit.MINUTES);

        System.out.println("Scheduled tasks:");
        System.out.println("- Update server list every " + listCheckInterval + " hours");
        System.out.println("- Checking servers every " + serversCheckInterval + " minutes");
    }

    private static void checkAllServers() {
        if (serverList.isEmpty()) {
            System.out.println("The server list is empty. Skipping checking.");
            return;
        }

        System.out.println("Checking " + serverList.size() + " servers...");

        int activeServers = 0;
        int serversWithPlayers = 0;

        for (FearServerListFetcher.FearServer server : serverList) {
            try {
                FearQuery.ServerStatus status = FearQuery.query(server.getIp(), server.getPort(), 5000);
                String serverKey = server.getIp() + ":" + server.getPort();

                if (status.online) {
                    activeServers++;
                    System.out.println("Server ONLINE: " + server.getName() + " (" + server.getIp() + ":" + server.getPort() +
                            ") Players: " + status.currentPlayers + "/" + status.maxPlayers +
                            " Map: " + status.map);

                    if (status.currentPlayers >= playerThreshold) {
                        serversWithPlayers++;
                        System.out.println("  -> Sufficient number of players (" + status.currentPlayers + " >= " + playerThreshold + ")");

                        ServerMessageInfo messageInfo = serverMessages.get(serverKey);

                        if (messageInfo == null) {
                            sendNewMessage(serverKey, server, status);
                        } else {
                            boolean needsUpdate = !status.map.equals(messageInfo.lastMap) ||
                                    status.currentPlayers != messageInfo.lastPlayerCount;

                            if (needsUpdate) {
                                System.out.println("  -> Needs updating (map or number of players has changed)");
                                updateExistingMessage(serverKey, messageInfo, server, status);
                            } else {
                                System.out.println("  -> No update required");
                            }
                        }
                    } else {
                        ServerMessageInfo messageInfo = serverMessages.get(serverKey);
                        if (messageInfo != null) {
                            System.out.println("  -> Not enough players (" + status.currentPlayers + " < " + playerThreshold + "), deleting the message");
                            deleteMessage(serverKey, messageInfo.messageId);
                        }
                    }
                } else {
                    ServerMessageInfo messageInfo = serverMessages.get(serverKey);
                    if (messageInfo != null) {
                        System.out.println("Server OFFLINE: " + server.getName() + " - deleting the message");
                        deleteMessage(serverKey, messageInfo.messageId);
                    } else {
                        System.out.println("Server OFFLINE: " + server.getName());
                    }
                }

                Thread.sleep(100);

            } catch (Exception e) {
                System.err.println("Error while checking the server " + server.getIp() + ":" +
                        server.getPort() + " (" + server.getName() + "): " + e.getMessage());

                String serverKey = server.getIp() + ":" + server.getPort();
                ServerMessageInfo messageInfo = serverMessages.get(serverKey);
                if (messageInfo != null) {
                    System.out.println("  -> Error while pinging, deleting message");
                    deleteMessage(serverKey, messageInfo.messageId);
                }
            }
        }

        System.out.println("Checking complete. Active servers: " + activeServers + "/" + serverList.size());
    }

    private static String createServerMessage(FearServerListFetcher.FearServer server, FearQuery.ServerStatus status) {
        return String.format(
                "**🎮 ACTIVE SERVER!**\n" +
                        "\n**Name:** %s\n" +
                        "**IP:** %s:%d\n" +
                        "**Map:** %s\n" +
                        "**Players:** %d/%d\n" +
                        "**Gamemode:** %s\n" +
                        "\n**Join the server!** 🚀",
                server.getName(),
                server.getIp(),
                server.getPort(),
                status.map,
                status.currentPlayers,
                status.maxPlayers,
                status.gameType
        );
    }

    private static void sendNewMessage(String serverKey, FearServerListFetcher.FearServer server, FearQuery.ServerStatus status) {
        if (targetChannel == null) return;

        String messageContent = createServerMessage(server, status);

        targetChannel.sendMessage(messageContent).queue(
                message -> {
                    ServerMessageInfo info = new ServerMessageInfo(
                            message.getIdLong(),
                            messageContent,
                            status.map,
                            status.currentPlayers
                    );
                    serverMessages.put(serverKey, info);
                    System.out.println("  -> A new message has been sent (ID: " + message.getIdLong() + ")");
                },
                error -> {
                    System.err.println("  -> Error sending new message: " + error.getMessage());
                }
        );
    }

    private static void updateExistingMessage(String serverKey, ServerMessageInfo messageInfo,
                                              FearServerListFetcher.FearServer server, FearQuery.ServerStatus status) {
        if (targetChannel == null) return;

        String newContent = createServerMessage(server, status);

        targetChannel.retrieveMessageById(messageInfo.messageId).queue(
                message -> {
                    message.editMessage(newContent).queue(
                            success -> {
                                messageInfo.lastContent = newContent;
                                messageInfo.lastMap = status.map;
                                messageInfo.lastPlayerCount = status.currentPlayers;
                                serverMessages.put(serverKey, messageInfo);
                                System.out.println("  -> Message updated (ID: " + messageInfo.messageId + ")");
                            },
                            error -> {
                                System.err.println("  -> Error updating message: " + error.getMessage());
                                serverMessages.remove(serverKey);
                                sendNewMessage(serverKey, server, status);
                            }
                    );
                },
                error -> {
                    System.err.println("  -> No messages found ID: " + messageInfo.messageId + ", deleting from memory");
                    serverMessages.remove(serverKey);
                    sendNewMessage(serverKey, server, status);
                }
        );
    }

    private static void deleteMessage(String serverKey, long messageId) {
        if (targetChannel == null) return;

        targetChannel.retrieveMessageById(messageId).queue(
                message -> {
                    message.delete().queue(
                            success -> {
                                serverMessages.remove(serverKey);
                                System.out.println("  -> Message has been deleted (ID: " + messageId + ")");
                            },
                            error -> {
                                System.err.println("  -> Error while deleting message: " + error.getMessage());
                                serverMessages.remove(serverKey);
                            }
                    );
                },
                error -> {
                    System.err.println("  -> No messages found to delete");
                    serverMessages.remove(serverKey);
                }
        );
    }
}