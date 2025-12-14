import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.ArrayList;
import java.io.File;

public class Server {
    private static ArrayList<String> clients = new ArrayList<>();
    private static HashMap<String, Socket> active_clients = new HashMap<>();
    private static long MAX_BUFFER_SIZE = 1048576000; // 1GB
    private static long MIN_CHUNK_SIZE = 2048; // 2KB
    private static long MAX_CHUNK_SIZE = 16384; // 16KB
    private static long current_buffer_size = 0;
    private static int fileId = 0;
    private static HashMap<Integer, String> uploading_files = new HashMap<>();
    private static int request_id = 0;
    private static HashMap<Integer, ArrayList<String>> file_requests = new HashMap<>();
    private static HashMap<String, ArrayList<String>> user_messages = new HashMap<>();

    public static void logout(String username) {
        active_clients.remove(username);
    }

    public static ArrayList<String> getUserLogData(String username) {
        ArrayList<String> log_data = new ArrayList<>();
        String log_file_path = "./UserData/" + username + "/log.txt";
        File log_file = new File(log_file_path);
        try (java.util.Scanner scanner = new java.util.Scanner(log_file)) {
            while (scanner.hasNextLine()) {
                log_data.add(scanner.nextLine());
            }
        } catch (IOException e) {
            System.out.println("An error occurred while reading the log file for user: " + username);
            e.printStackTrace();
        }
        return log_data;
    }

    public static void discard_upload(long file_size, int fileId, String path_name, boolean finished) {
        current_buffer_size -= file_size;
        uploading_files.remove(fileId);

        if (finished) {
            return;
        }
        File file = new File(path_name);
        if (file.delete()) {
            System.out.println("Partially uploaded file deleted: " + path_name);
        } else {
            System.out.println("Failed to delete partially uploaded file: " + path_name);
        }
        System.out.println("Upload discarded for file ID: " + fileId + ". Freed " + file_size + " bytes.");
    }

    public static int getRequestId() {
        return request_id++;
    }

    public static long getMaxChunkSize() {
        return MAX_CHUNK_SIZE;
    }

    public static void removeRequest(int req_id) {
        file_requests.remove(req_id);
    }

    public static ArrayList<String> getUserMessages(String username) {
        ArrayList<String> messages = new ArrayList<>();
        if (user_messages.containsKey(username)) {
            messages = user_messages.get(username);
            user_messages.remove(username);
        }
        return messages;
    }

    public static void send_message(int request_id, String sender) {
        ArrayList<String> message_details = new ArrayList<>();
        message_details.add("\nYour request with ID " + request_id + " has been fulfilled.");
        message_details.add("Uploaded by: " + sender);

        String recipient = file_requests.get(request_id).get(0);
        user_messages.putIfAbsent(recipient, message_details);
    }

    public static void addFileRequest(int req_id, String requester, String recipient, String description) {
        ArrayList<String> request_details = new ArrayList<>();
        request_details.add(requester);
        request_details.add(recipient);
        request_details.add(description);
        file_requests.put(req_id, request_details);
    }

    public static ArrayList<String> viewRequests(String username) {
        ArrayList<String> requests_for_user = new ArrayList<>();
        for (int req_id : file_requests.keySet()) {
            ArrayList<String> details = file_requests.get(req_id);
            if ((details.get(1).equals(username) ||
                    details.get(1).equals("ALL"))
                    && !details.get(0).equals(username)) {
                String request_info = "Request ID: " + req_id + ", From: " + details.get(0) + ", Description: "
                        + details.get(2);
                requests_for_user.add(request_info);
            }
        }
        return requests_for_user;
    }

    public static boolean addFileinUserData(String path_name) {
        File file = new File(path_name);
        try {
            if (file.createNewFile()) {
                System.out.println("File created: " + file.getName());
                return true;
            } else {
                System.out.println("File already exists.");
                return false;
            }
        } catch (IOException e) {
            System.out.println("An error occurred.");
            e.printStackTrace();
            return false;
        }
    }

    public static Object[] is_upload_possible(long file_size, String fileName) {
        if (file_size + current_buffer_size <= MAX_BUFFER_SIZE) {
            // randomly generates a chunk size (between MIN_CHUNK_SIZE and MAX_CHUNK_SIZE)
            long chunk_size = MIN_CHUNK_SIZE
                    + (long) (Math.random() * (MAX_CHUNK_SIZE - MIN_CHUNK_SIZE + 1));
            current_buffer_size += file_size;
            uploading_files.put(fileId, fileName);
            return new Object[] { chunk_size, fileId++ };
        } else {
            return new Object[] { -1L, -1 };
        }
    }

    public static ArrayList<String> list_public_files(String username) {
        ArrayList<String> public_files = new ArrayList<>();
        String user_dir_path = "./UserData/" + username + "/public/";
        File user_dir = new File(user_dir_path);
        File[] files = user_dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    public_files.add(file.getName());
                }
            }
        }
        return public_files;
    }

    public static ArrayList<String> list_private_files(String username) {
        ArrayList<String> private_files = new ArrayList<>();
        String user_dir_path = "./UserData/" + username + "/private/";
        File user_dir = new File(user_dir_path);
        File[] files = user_dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    private_files.add(file.getName());
                }
            }
        }
        return private_files;
    }

    public static HashMap<String, ArrayList<String>> look_up_others_files(String ownUserName) {
        HashMap<String, ArrayList<String>> others_files = new HashMap<>();
        for (String user : clients) {
            if (user.equals(ownUserName)) {
                continue;
            }
            ArrayList<String> public_files = list_public_files(user);
            if (public_files.isEmpty()) {
                continue;
            }
            others_files.put(user, public_files);
        }
        return others_files;
    }

    public static HashMap<String, String> send_client_list(Socket requester_socket) {
        HashMap<String, String> clients_list = new HashMap<>();
        String requester_username = "";
        // find requester username
        for (String user : active_clients.keySet()) {
            if (active_clients.get(user).equals(requester_socket)) {
                requester_username = user;
                break;
            }
        }
        // prepare client list
        for (String user : clients) {
            if (active_clients.containsKey(user)) {
                if (user.equals(requester_username)) {
                    continue;
                }
                clients_list.put(user, "Online");
            } else {
                clients_list.put(user, "Offline");
            }
        }
        return clients_list;
    }

    public static void create_directory(String dir_name) {
        File directory = new File(dir_name);
        if (!directory.exists()) {
            boolean created = directory.mkdirs();
            if (created) {
                System.out.println("Directory created named: " + dir_name);
            } else {
                System.err.println("Failed to create directory: " + dir_name);
            }
        } else {
            System.out.println("Directory already exists: " + dir_name);
        }
    }

    public static boolean isUserLoggedIn(String username) {
        return active_clients.containsKey(username);
    }

    public static boolean login(String username, Socket socket) {
        if (isUserLoggedIn(username)) {
            return true;
        } else {
            // Completely New User
            if (!clients.contains(username)) {
                System.out.println("New user signed up: " + username);
                clients.add(username);
                // Create user directories
                create_directory("./UserData/" + username + "/public/");
                create_directory("./UserData/" + username + "/private/");
                // Create a file to store user data
                addFileinUserData("./UserData/" + username + "/log.txt");
            }
            active_clients.put(username, socket);
            return false;
        }
    }

    public static void populate_clients_from_directories() {
        File userDataDir = new File("./UserData/");
        File[] userDirs = userDataDir.listFiles(File::isDirectory);
        if (userDirs != null) {
            for (File userDir : userDirs) {
                clients.add(userDir.getName());
            }
        }
    }

    public static void main(String[] args) throws IOException, ClassNotFoundException {
        ServerSocket welcomeSocket = new ServerSocket(6666);

        // Create main directory for storing user directories
        create_directory("UserData/");

        // Populate Client List from existing directories
        populate_clients_from_directories();

        while (true) {
            System.out.println("Waiting for connection...");
            Socket socket = welcomeSocket.accept();
            System.out.println("Connection established");

            // open thread
            Thread worker = new Worker(socket);
            worker.start();

        }
    }
}
