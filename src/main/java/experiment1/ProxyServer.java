package experiment1;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;

public class ProxyServer {
    public static int PORT = 8213;
    public static HashMap<String, String> dnsCache = new HashMap<String, String>();
    public static Object lock = new Object();

    public static void main(String[] args) {
        try {
            System.out.println("STARTING HTTPS PROXY SERVER on PORT:" + PORT);
            ServerSocket serverSocket = new ServerSocket(PORT,50, InetAddress.getByName("127.0.0.1"));
            System.out.println("LISTENING on PORT:" + PORT);
            while (true) {
                Socket request = serverSocket.accept();
                new RequestThread(request).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

