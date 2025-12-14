import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Worker extends Thread {
    Socket socket;
    private static int onGoingFileId = -1;
    private static long onGoingFileSize = -1;
    private static String onGoingPath = "";
    private static String currentUser = "";
    private static String onGoingFileName = "";
    private static boolean uploadInProgress = false;
    private static boolean downloadInProgress = false;

    private void receive_file(String username, ObjectInputStream in, ObjectOutputStream out)
            throws IOException, ClassNotFoundException {
        // receive file upload
        String file_name = (String) in.readObject();
        onGoingFileName = file_name;
        long file_size = (long) in.readObject();
        onGoingFileSize = file_size;
        System.out.println("Received upload request: " + file_name + " of size " + file_size
                + " bytes");
        Object[] upload_info = Server.is_upload_possible(file_size, file_name);
        long chunk_size = (long) upload_info[0];
        System.out.println("Allocated chunk size: " + chunk_size + " bytes");
        onGoingFileId = (int) upload_info[1];
        out.writeObject(chunk_size);
        out.flush();

        String file_type = "";
        if (chunk_size == -1L) {
            System.out.println("Upload not possible at the moment. Buffer full.");
            return;
        } else {
            file_type = (String) in.readObject();
            System.out.println("File type: " + file_type);
        }

        String user_dir_path = "./UserData/" + username + "/";
        if (file_type.equals("public")) {
            user_dir_path += "public/" + file_name;
        } else {
            user_dir_path += "private/" + file_name;
        }
        onGoingPath = user_dir_path;
        uploadInProgress = true;

        FileOutputStream fos = new FileOutputStream(user_dir_path);
        BufferedOutputStream bos = new BufferedOutputStream(fos);
        int bytes_received = 0;

        while (true) {
            Object object = in.readObject();
            if (object.equals("COMPLETE")) {
                break;
            }
            byte[] chunk = (byte[]) object;
            bytes_received += chunk.length;
            bos.write(chunk);
            out.writeObject(chunk.length);
        }

        if (bytes_received == file_size) {
            System.out.println("File " + file_name + " received successfully.");
            // Server.addFileinUserData(user_dir_path);
            out.writeObject("SUCCESS");
            out.flush();
            bos.flush();
        } else {
            System.out.println("File " + file_name + " received with errors.");
            out.writeObject("ERROR");
            out.flush();
        }
        bos.close();

        uploadInProgress = false;
        Server.discard_upload(file_size, onGoingFileId, onGoingPath, true);
        onGoingFileId = -1;
        onGoingFileSize = -1;
        onGoingPath = "";
        onGoingFileName = "";

        FileOutputStream logStream = new FileOutputStream("UserData/" + username + "/log.txt", true);
        logStream.write(
                ("# File Upload: " + file_name + ", Date & Time: " +
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        + " Status: SUCCESS" + "\n")
                        .getBytes());
        logStream.close();
    }

    private void send_file(String username, String owener_name, String file_name, String file_type,
            ObjectInputStream in, ObjectOutputStream out)
            throws IOException, ClassNotFoundException {
        String path = "./UserData/" + owener_name + "/";
        if (file_type.equals("public")) {
            path += "public/" + file_name;
        } else {
            path += "private/" + file_name;
        }
        byte[] file_bytes = Files.readAllBytes(Paths.get(path));
        int total_bytes = file_bytes.length;
        int offset = 0;
        int chunk_size = (int) Server.getMaxChunkSize();
        downloadInProgress = true;
        while (offset < total_bytes) {
            byte[] chunk;
            if (offset + chunk_size <= total_bytes) {
                chunk = new byte[(int) chunk_size];
                System.arraycopy(file_bytes, offset, chunk, 0, (int) chunk_size);
                offset += chunk_size;
            } else {
                int remaining = total_bytes - offset;
                chunk = new byte[remaining];
                System.arraycopy(file_bytes, offset, chunk, 0, remaining);
                offset += remaining;
            }
            out.writeObject(chunk);
            out.flush();
        }

        out.writeObject("COMPLETE");
        out.flush();
        downloadInProgress = false;

        FileOutputStream logStream = new FileOutputStream("UserData/" + username + "/log.txt", true);
        logStream.write(
                ("# File Download: " + file_name + ", Date & Time: " +
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        + " Status: SUCCESS" + "\n")
                        .getBytes());
        logStream.close();
    }

    public Worker(Socket socket) {
        this.socket = socket;
    }

    public void run() {
        // buffers
        try {
            ObjectOutputStream out = new ObjectOutputStream(this.socket.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(this.socket.getInputStream());

            String username = (String) in.readObject();
            System.out.println("Username received: " + username);
            currentUser = username;

            outer: while (true) {
                // read username
                try {

                    if (Server.login(username, this.socket)) {
                        System.out.println("User --> " + username + " already logged in. Connection terminated.");
                        out.writeObject("User already logged in. Connection Terminated.");
                        out.flush();
                        socket.close();
                        break;
                    } else {
                        out.writeObject("Welcome " + username + "!");
                        out.flush();
                        while (true) {
                            String option = (String) in.readObject();
                            if (option.equals("1")) {
                                // send list of clients
                                HashMap<String, String> clients_list = new HashMap<>();
                                clients_list = Server.send_client_list(this.socket);
                                out.writeObject(clients_list);
                                out.flush();
                            } else if (option.equals("2")) {
                                // send list of own files
                                ArrayList<String> public_files = Server.list_public_files(username);
                                ArrayList<String> private_files = Server.list_private_files(username);
                                HashMap<String, ArrayList<String>> files_map = new HashMap<>();
                                files_map.put("public", public_files);
                                files_map.put("private", private_files);
                                out.writeObject(files_map);
                                out.flush();
                            } else if (option.equals("3")) {
                                // send list of others' public files
                                HashMap<String, ArrayList<String>> others_files = Server.look_up_others_files(username);
                                out.writeObject(others_files);
                                out.flush();
                            } else if (option.equals("4")) {
                                receive_file(username, in, out);
                            } else if (option.equals("5")) {
                                String download_type = (String) in.readObject();
                                if (download_type.equals("OWN")) {
                                    String file_name = (String) in.readObject();
                                    String file_type = (String) in.readObject();
                                    send_file(username, username, file_name, file_type, in, out);
                                } else if (download_type.equals("OTHERS")) {
                                    String owner_username = (String) in.readObject();
                                    String file_name = (String) in.readObject();
                                    send_file(username, owner_username, file_name, "public", in, out);
                                } else {
                                    System.out.println("Invalid download type received.");
                                    continue;
                                }
                            } else if (option.equals("6")) {
                                String recipient = (String) in.readObject();
                                String description = (String) in.readObject();
                                int req_id = Server.getRequestId();
                                Server.addFileRequest(req_id, username, recipient, description);
                            } else if (option.equals("7")) {
                                ArrayList<String> requests = Server.viewRequests(username);
                                out.writeObject(requests);
                                out.flush();
                            } else if (option.equals("8")) {
                                String request_id_str = (String) in.readObject();
                                int request_id = Integer.parseInt(request_id_str);
                                receive_file(username, in, out);
                                Server.send_message(request_id, username);
                                Server.removeRequest(request_id);
                            } else if (option.equals("9")) {
                                ArrayList<String> messages = Server.getUserMessages(username);
                                out.writeObject(messages);
                                out.flush();
                            } else if (option.equals("10")) {
                                ArrayList<String> log_data = Server.getUserLogData(username);
                                out.writeObject(log_data);
                                out.flush();
                            } else if (option.equals("11")) {
                                Server.logout(username);
                                System.out.println("User " + username + " logged out.");
                                currentUser = "";
                                in.close();
                                out.close();
                                socket.close();
                                break outer;
                            }

                        }
                    }

                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                } catch (java.io.EOFException e) {
                    System.out.println(username + " got disconnected!");
                    currentUser = "";
                    Server.logout(username);
                    FileOutputStream logStream = new FileOutputStream("UserData/" + username + "/log.txt", true);
                    if (uploadInProgress) {
                        Server.discard_upload(onGoingFileSize, onGoingFileId, onGoingPath, false);
                        logStream.write(
                                ("# File Upload: " + onGoingFileName + ", Date & Time: " +
                                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                        + " Status: FAILED" + "\n")
                                        .getBytes());

                    } else if (downloadInProgress) {
                        logStream.write(
                                ("# File Download: " + onGoingFileName + ", Date & Time: " +
                                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                        + " Status: FAILED" + "\n")
                                        .getBytes());

                        downloadInProgress = false;
                    }
                    logStream.close();
                    break;
                }
            }
        } catch (Exception e) {
            if (e instanceof SocketException) {
                System.out.println(currentUser + " got disconnected!!");
                Server.discard_upload(onGoingFileSize, onGoingFileId, onGoingPath, false);
                Server.logout(currentUser);
                FileOutputStream logStream;
                if (uploadInProgress) {
                    try {
                        logStream = new FileOutputStream("UserData/" + currentUser + "/log.txt", true);
                        logStream.write(
                                ("# File Upload: " + onGoingFileName + ", Date & Time: " +
                                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                        + " Status: FAILED" + "\n")
                                        .getBytes());
                        logStream.close();
                    } catch (IOException ioException) {
                        ioException.printStackTrace();
                    }
                } else if (downloadInProgress) {
                    try {
                        logStream = new FileOutputStream("UserData/" + currentUser + "/log.txt", true);
                        logStream.write(
                                ("# File Download: " + onGoingFileName + ", Date & Time: " +
                                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                        + " Status: FAILED" + "\n")
                                        .getBytes());
                        logStream.close();
                    } catch (IOException ioException) {
                        ioException.printStackTrace();
                    }
                }
            }

            // e.printStackTrace();
        }
    }
}
