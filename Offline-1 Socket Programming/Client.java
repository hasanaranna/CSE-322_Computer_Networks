import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;

public class Client {
    private static void send_file(boolean is_requested, ObjectInputStream in, ObjectOutputStream out)
            throws IOException, ClassNotFoundException {
        System.out.println("Write the full file path: ");
        String file_path = System.console().readLine();
        String file_name = "";
        long file_size = -1;
        try {
            file_name = Paths.get(file_path).getFileName().toString();
            file_size = Files.size(Paths.get(file_path));
        } catch (IOException e) {
            file_size = -1;
        }
        if (file_size < 0) {
            System.out.println("File does not exist. Please try again.");
            return;
        }
        System.out.println("File Name: " + file_name + ", File Size: " + file_size + " bytes");
        // Send file name and size to server
        out.writeObject(file_name);
        out.flush();
        out.writeObject(file_size);
        out.flush();
        // Send file data
        long chunk_size = (long) in.readObject();

        if (chunk_size == -1L) {
            System.out.println("Upload not possible at the moment. Buffer full.");
            return;
        } else {
            System.out.println("Upload possible. Chunk size: " + chunk_size + " bytes");
            if (!is_requested) {
                System.out.println("How do you want to upload the file? 1. Public 2. Private");
                String choice = System.console().readLine();
                if (choice.equals("1")) {
                    out.writeObject("public");
                } else if (choice.equals("2")) {
                    out.writeObject("private");
                }
            } else {
                out.writeObject("public");
            }
            out.flush();
        }
        byte[] file_bytes = Files.readAllBytes(Paths.get(file_path));
        int total_bytes = file_bytes.length;
        int offset = 0;
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
            int bytes_sent = chunk.length;
            System.out.println("Sent chunk of size: " + bytes_sent + " bytes");
            int acknowledged_bytes = (int) in.readObject();
            if (acknowledged_bytes != bytes_sent) {
                System.out.println("Error in transmission. Exiting...");
                return;
            } else {
                System.out.println("Acknowledged " + acknowledged_bytes + " bytes");
            }
        }

        out.writeObject("COMPLETE");
        out.flush();
        String status = (String) in.readObject();
        if (status.equals("SUCCESS")) {
            System.out.println("File uploaded successfully.");
        } else {
            System.out.println("File upload failed with errors.");
        }
    }

    private static void download_file(ObjectInputStream in, ObjectOutputStream out)
            throws IOException, ClassNotFoundException {
        System.out.println("Download options: 1. Own File 2. Others' Public File");
        String choice = System.console().readLine();
        String file_name = "";
        if (choice.equals("1")) {
            out.writeObject("OWN");
            System.out.println("Enter the file name to download: ");
            file_name = System.console().readLine();
            out.writeObject(file_name);
            out.flush();
            System.out.println("Enter the file type (public/private): ");
            String file_type = System.console().readLine();
            out.writeObject(file_type);
            out.flush();
        } else if (choice.equals("2")) {
            out.writeObject("OTHERS");
            System.out.println("Enter the owner's username: ");
            String owner_username = System.console().readLine();
            out.writeObject(owner_username);
            out.flush();
            System.out.println("Enter the public file name to download: ");
            file_name = System.console().readLine();
            out.writeObject(file_name);
            out.flush();
        } else {
            out.writeObject("abort");
            return;
        }

        String path = "./Downloads/";
        File download = new File(path);
        if (!download.exists()) {
            download.mkdir();
        }

        FileOutputStream fos = new FileOutputStream(path + file_name);
        BufferedOutputStream bos = new BufferedOutputStream(fos);

        while (true) {
            Object object = in.readObject();
            if (object.equals("COMPLETE")) {
                bos.flush();
                bos.close();
                break;
            }
            byte[] chunk = (byte[]) object;

            bos.write(chunk);
        }

        bos.flush();
        bos.close();

        System.out.println("File " + file_name + " downloaded successfully.");
    }

    public static void main(String[] args) throws IOException, ClassNotFoundException {
        Socket socket = new Socket("localhost", 6666);
        System.out.println("Connection established");
        System.out.println("Remote port: " + socket.getPort());
        System.out.println("Local port: " + socket.getLocalPort());

        // buffers
        ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
        ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

        // Enter username
        String username = "";
        System.out.println("Enter username: ");
        username = System.console().readLine();
        if (username == null || username.isEmpty()) {
            System.out.println("Username cannot be empty. Exiting...");
            socket.close();
            return;
        }

        // login by sending username
        out.writeObject(username);
        out.flush();

        while (true) {

            String response = (String) in.readObject();
            System.out.println("\nServer response: " + response);

            if (response.startsWith("User already logged in")) {
                socket.close();
                break;

            } else {
                // successfully logged in
                System.out.println("Logged in as " + username);

                while (true) {
                    System.out.println("\nSelect an option:");
                    String[] options = { "1. Look up clients", "2. Look up own files",
                            "3. Look up other's public files", "4. Upload file", "5. Download file", "6. Request file",
                            "7. View requests", "8. Upload Requested File", "9. View Messages", "10. View History",
                            "11. Logout\n" };
                    for (String option : options) {
                        System.out.println(option);
                    }
                    String choice = System.console().readLine();
                    if (choice.equals("1")) {
                        out.writeObject("1");
                        out.flush();
                        @SuppressWarnings("unchecked")
                        HashMap<String, String> clients_list = (HashMap<String, String>) in.readObject();
                        System.out.println("\nClient List:");
                        for (String user : clients_list.keySet()) {
                            System.out.println(user + " - " + clients_list.get(user));
                        }
                    } else if (choice.equals("2")) {
                        out.writeObject("2");
                        out.flush();
                        @SuppressWarnings("unchecked")
                        HashMap<String, ArrayList<String>> files_map = (HashMap<String, ArrayList<String>>) in
                                .readObject();
                        System.out.println("\nOwn Public Files:");
                        for (String file : files_map.get("public")) {
                            System.out.println(file);
                        }
                        System.out.println("\nOwn Private Files:");
                        for (String file : files_map.get("private")) {
                            System.out.println(file);
                        }
                    } else if (choice.equals("3")) {
                        out.writeObject("3");
                        out.flush();
                        @SuppressWarnings("unchecked")
                        HashMap<String, ArrayList<String>> others_files = (HashMap<String, ArrayList<String>>) in
                                .readObject();
                        System.out.println("\nOthers' Public Files:");
                        for (String user : others_files.keySet()) {
                            System.out.println("User: " + user);
                            for (String file : others_files.get(user)) {
                                System.out.println(" - " + file);
                            }
                        }
                    } else if (choice.equals("4")) {
                        out.writeObject("4");
                        out.flush();
                        send_file(false, in, out);
                    } else if (choice.equals("5")) {
                        out.writeObject("5");
                        out.flush();
                        download_file(in, out);
                    } else if (choice.equals("6")) {
                        String recipient = "";
                        System.out.println("Enter the username of the recipient: ");
                        recipient = System.console().readLine();
                        if (recipient == null || recipient.isEmpty()) {
                            System.out.println("Recipient username cannot be empty. Please try again.");
                            continue;
                        }
                        String description = "";
                        System.out.println("Enter a description for the file request: ");
                        description = System.console().readLine();
                        if (description == null || description.isEmpty()) {
                            System.out.println("Description cannot be empty. Please try again.");
                            continue;
                        }
                        out.writeObject("6");
                        out.flush();
                        out.writeObject(recipient);
                        out.flush();
                        out.writeObject(description);
                        out.flush();
                    } else if (choice.equals("7")) {
                        out.writeObject("7");
                        out.flush();
                        @SuppressWarnings("unchecked")
                        ArrayList<String> requests = (ArrayList<String>) in
                                .readObject();
                        System.out.println("File Requests:");
                        for (String request_info : requests) {
                            System.out.println(request_info);
                        }
                    } else if (choice.equals("8")) {
                        out.writeObject("8");
                        out.flush();
                        System.out.println("Enter the Request ID to upload the requested file: ");
                        String request_id = System.console().readLine();
                        out.writeObject(request_id);
                        out.flush();
                        send_file(true, in, out);
                    } else if (choice.equals("9")) {
                        out.writeObject("9");
                        out.flush();
                        @SuppressWarnings("unchecked")
                        ArrayList<String> messages = (ArrayList<String>) in.readObject();
                        System.out.println("Messages:");
                        for (String message : messages) {
                            System.out.println(message);
                        }
                    } else if (choice.equals("10")) {
                        out.writeObject("10");
                        out.flush();
                        System.out.println("History");
                        @SuppressWarnings("unchecked")
                        ArrayList<String> history = (ArrayList<String>) in.readObject();
                        for (String record : history) {
                            System.out.println(record);
                        }
                    } else if (choice.equals("11")) {
                        System.out.println("Logging out...");
                        out.writeObject("11");
                        in.close();
                        out.close();
                        socket.close();
                        return;
                    } else {
                        System.out.println("Invalid option. Please try again.");
                    }

                }
            }
        }
    }
}
