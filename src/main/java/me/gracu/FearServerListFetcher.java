package me.gracu;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class FearServerListFetcher {

    public static class FearServer {
        private String ip;
        private int port;
        private String name;
        private String admin;
        private String version;

        public FearServer(String ip, int port, String name, String admin, String version) {
            this.ip = ip;
            this.port = port;
            this.name = name;
            this.admin = admin;
            this.version = version;
        }

        public String getIp() { return ip; }
        public int getPort() { return port; }
        public String getName() { return name; }
        public String getAdmin() { return admin; }
        public String getVersion() { return version; }

        @Override
        public String toString() {
            return String.format("IP: %-18s Port: %-6s Name: %-30s Admin: %-12s Version: %s",
                    ip, port, name, admin, version);
        }
    }

    public static boolean fetchAndSaveServerList() {
        String url = "https://fear-community.org/api/serverlistmanager/index.php";
        String outputJsonFile = "fear_server_list.json";

        try {
            System.out.println("Downloading the list of servers from: " + url);
            List<FearServer> serverList = fetchAndParseHtml(url);

            if (serverList.isEmpty()) {
                System.out.println("Warning: No server data found in response.");
                return false;
            }

            System.out.println("Found " + serverList.size() + " servers.");

            saveAsJson(serverList, outputJsonFile);
            System.out.println("List saved to a JSON file: " + outputJsonFile);

            return true;

        } catch (IOException e) {
            System.err.println("Error while downloading the server list: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private static List<FearServer> fetchAndParseHtml(String url) throws IOException {
        List<FearServer> servers = new ArrayList<>();

        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(10000)
                .get();

        Element table = doc.select("table").first();
        if (table == null) {
            System.err.println("No table found on the page.");
            return servers;
        }

        Elements rows = table.select("tr");

        for (int i = 1; i < rows.size(); i++) {
            Element row = rows.get(i);
            Elements cells = row.select("td");

            if (cells.size() >= 6) {
                try {
                    String ip = cells.get(1).text().trim();
                    String portText = cells.get(2).text().trim();

                    String cleanPortText = portText.replaceAll("[^0-9]", "");

                    if (cleanPortText.isEmpty()) {
                        continue;
                    }

                    int port = Integer.parseInt(cleanPortText);
                    String name = cells.get(3).text().trim();
                    String admin = cells.get(4).text().trim();
                    String version = cells.get(5).text().trim();

                    if (ip.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) {
                        servers.add(new FearServer(ip, port, name, admin, version));
                    } else {
                        System.err.println("Line omitted " + i + " with an incorrect IP address: " + ip);
                    }

                } catch (NumberFormatException e) {
                    System.err.println("The port cannot be parsed. '" + cells.get(2).text() + "' in the line " + i);
                } catch (Exception e) {
                    System.err.println("Row processing error " + i + ": " + e.getMessage());
                }
            }
        }

        System.out.println("Total servers found: " + servers.size());
        return servers;
    }

    private static void saveAsJson(List<FearServer> serverList, String filename) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (FileWriter writer = new FileWriter(filename)) {
            gson.toJson(serverList, writer);
        }
    }
}
