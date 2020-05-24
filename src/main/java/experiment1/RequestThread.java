package experiment1;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.HashSet;

public class RequestThread extends Thread {
    public static int BUFFER_SIZE = 10000;
    Socket clientRequestSocket;

    public RequestThread(Socket request) {
        this.clientRequestSocket = request;
    }

    public static String findFastestOfAllIPs(String host, String[] clientIPStrings, int port) {
        if (clientIPStrings.length == 1)
            return clientIPStrings[0];
        String fastestIP = clientIPStrings[0];
        Long fastestResponseTime = Long.MAX_VALUE;
        for (String clientIP : clientIPStrings) {
            String response = findSpeedOfIpAddress(host, clientIP,
                    fastestResponseTime == Long.MAX_VALUE ? 512 : fastestResponseTime, port);
            Long tempFasttestResponseTime = Long.parseLong(response.split(";")[1]);
            if (tempFasttestResponseTime < fastestResponseTime) {
                fastestResponseTime = tempFasttestResponseTime;
                fastestIP = response.split(";")[0];
            }
        }
        return fastestIP;
    }

    public static String findSpeedOfIpAddress(String host, String IP, Long timeOut, int port) {
        try {
            long startTime = System.currentTimeMillis();
            Socket socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.setSoTimeout((int) (512 < timeOut ? 512 : timeOut));
            socket.connect(new InetSocketAddress(InetAddress.getByName(IP), port));
            long timeTakenByIP = System.currentTimeMillis() - startTime;
            System.out.println("Time Taken by " + IP + " of host " + host + " is " + timeTakenByIP + " ms");
            try {
                socket.close();
            } catch (Exception e) {
            }
            return IP + ";" + timeTakenByIP;
        } catch (Exception e) {
            System.out.println(IP + " of " + host + " was super slow");
        }
        return IP + ";" + Integer.MAX_VALUE;
    }

    public static String[] findAllIPAddressOfDomain(String domain, String[] clientIPForDNSQuery) {
        HashSet<String> ipList = new HashSet<String>();
        boolean isLikelyUsingCDN = false;
        for (String clientIP : clientIPForDNSQuery) {
            try {
                HttpsURLConnection connectionToDoHServer = (HttpsURLConnection) new URL(
                        "https://8.8.8.8/resolve?name=" + domain + "&type=A&ecs=" + clientIP).openConnection();
                connectionToDoHServer.setConnectTimeout(1000);
                connectionToDoHServer.setReadTimeout(1000);
                BufferedReader in = new BufferedReader(new InputStreamReader(connectionToDoHServer.getInputStream()));
                String inputLine;
                StringBuffer responseFromDoHServer = new StringBuffer();
                while ((inputLine = in.readLine()) != null) {
                    responseFromDoHServer.append(inputLine);
                }
                in.close();
                String response = responseFromDoHServer.toString();
                //System.out.println("DOH response " + response);
                connectionToDoHServer.disconnect();
                JSONObject responseJson = (JSONObject) new JSONParser().parse(response);
                JSONArray responseAnswerJsonArray = (JSONArray) responseJson.get("Answer");
                for (Object object : responseAnswerJsonArray) {
                    Long answerType = (Long) ((JSONObject) object).get("type");
                    String answerData = (String) ((JSONObject) object).get("data");
                    if (answerType == 1 && !isLikelyUsingCDN) {
                        ipList.add(answerData);
                        break;
                    }
                    if (answerType == 1 && isLikelyUsingCDN) {
                        ipList.add(answerData);
                    }
                    if (answerType == 5) {
                        isLikelyUsingCDN = true;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        String[] result = new String[ipList.size()];
        ipList.toArray(result);
        return result;
    }

    public static String getIPv4FromDoH(String host, int port) {
        try {
            String cachedEntry = ProxyServer.dnsCache.get(host);
            if (cachedEntry != null) {
                System.out.println("Resolved from cache: " + host + " = " + cachedEntry);
                return cachedEntry;
            }
            String[] allIPAddressesOfDomain = findAllIPAddressOfDomain(host, new String[]{"", "8.8.8.8"});
            // NEEDS REFACTORING - ONLY FOR PERFORMANCE IN RACE CONDITIONS
            ProxyServer.dnsCache.put(host, allIPAddressesOfDomain[0]);
            synchronized (ProxyServer.lock) {
                String ip = findFastestOfAllIPs(host, allIPAddressesOfDomain,
                        port);
                System.out.println("Resolved fastest IP for " + host + " as " + ip);
                ProxyServer.dnsCache.put(host, ip);
                return ip;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return host;
    }

    public void run() {
        try {
            InputStream clientRequestSocketInputStream = clientRequestSocket.getInputStream();
            OutputStream clientRequestSocketOutputStream = clientRequestSocket.getOutputStream();

            byte[] requestHeaders = new byte[BUFFER_SIZE];
            byte[] tempBuffer = new byte[BUFFER_SIZE];
            int requestHeadersReadLength = 0;
            int tempBufferReadLength = 0;

            do {
                tempBufferReadLength = clientRequestSocketInputStream.read(tempBuffer, 0, BUFFER_SIZE);
                if (tempBufferReadLength > 0)
                    System.arraycopy(tempBuffer, 0, requestHeaders, requestHeadersReadLength, tempBufferReadLength);
                else
                    break;
                requestHeadersReadLength += tempBufferReadLength;
            } while (requestHeaders[requestHeadersReadLength - 1] != '\n'
                    && requestHeaders[requestHeadersReadLength - 2] != '\r'
                    && requestHeaders[requestHeadersReadLength - 3] != '\n'
                    && requestHeaders[requestHeadersReadLength - 4] != '\r');
            String[] lineInHeaders = new String(requestHeaders).substring(0, requestHeadersReadLength).split("\r\n");
            String method = lineInHeaders[0].split(" ")[0];
            String path = lineInHeaders[0].split(" ")[1];
            // String httpVersion = lineInHeaders[0].split(" ")[2];

            System.out.println(method + " " + path);

            String actualHost = path;
            String hostNameInActualHost = actualHost.split(":")[0];
            String portInActualHost = actualHost.split(":").length > 1 ? actualHost.split(":")[1] : "80";
            Socket serverRequestSocket = new Socket();
            serverRequestSocket.setTcpNoDelay(true);
            serverRequestSocket.setSoTimeout(2000);
            serverRequestSocket.connect(
                    new InetSocketAddress(getIPv4FromDoH(hostNameInActualHost, Integer.valueOf(portInActualHost)),
                            Integer.valueOf(portInActualHost)));
            System.out.println("CONNECTED " + hostNameInActualHost + ":" + portInActualHost);
            clientRequestSocketOutputStream
                    .write("HTTP/1.0 200 Connection Established\r\nConnection: close\r\n\r\n".getBytes());
            OutputStream serverRequestSocketOutputStream = serverRequestSocket.getOutputStream();

            InputStream serverRequestSocketInputStream = serverRequestSocket.getInputStream();

            Thread clientToServerThread = new Thread() {
                public void run() {
                    try {
                        byte[] tempBuffer = new byte[BUFFER_SIZE];
                        int tempBufferReadLength = 0;
                        while (true) {
                            tempBufferReadLength = clientRequestSocketInputStream.read(tempBuffer, 0, BUFFER_SIZE);
                            if (tempBufferReadLength > 0)
                                serverRequestSocketOutputStream.write(tempBuffer, 0, tempBufferReadLength);
                            else
                                break;
                        }
                    } catch (Exception e) {
                    } finally {
                        try {
                            clientRequestSocketInputStream.close();
                        } catch (IOException e) {
                        }
                        try {
                            serverRequestSocketOutputStream.close();
                        } catch (IOException e) {
                        }
                    }
                }
            };
            Thread serverToClientThread = new Thread() {
                public void run() {
                    try {
                        byte[] tempBuffer = new byte[BUFFER_SIZE];
                        int tempBufferReadLength = 0;
                        tempBufferReadLength = serverRequestSocketInputStream.read(tempBuffer, 0, BUFFER_SIZE);
                        clientRequestSocketOutputStream.write(tempBuffer, 0, tempBufferReadLength);
                        while (true) {
                            tempBufferReadLength = serverRequestSocketInputStream.read(tempBuffer, 0, BUFFER_SIZE);
                            if (tempBufferReadLength > 0)
                                clientRequestSocketOutputStream.write(tempBuffer, 0, tempBufferReadLength);
                            else
                                break;
                        }
                    } catch (Exception e) {
                    } finally {
                        try {
                            clientRequestSocketOutputStream.close();
                        } catch (IOException e) {
                        }
                        try {
                            serverRequestSocketInputStream.close();
                        } catch (IOException e) {
                        }
                    }
                }
            };
            clientToServerThread.start();
            serverToClientThread.start();
            clientToServerThread.join();
            serverToClientThread.join();
            serverRequestSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
