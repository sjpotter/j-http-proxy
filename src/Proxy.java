import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.StringTokenizer;
import java.util.zip.GZIPInputStream;

public class Proxy implements Runnable {
    private Socket socket = null;
    private static final int BUFFER_SIZE = 32768;
    
    public Proxy(Socket socket) {
        this.socket = socket;
    }

    public static void main(String[] args) throws UnknownHostException, IOException {
        ServerSocket control = new ServerSocket(8080, 0, InetAddress.getByName("0.0.0.0"));
        
        while (true) {
            Socket socket = control.accept();
            
            Thread proxy = new Thread(new Proxy(socket));
            proxy.start();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void run() {
        boolean post = false;
        int length = -1;
        try {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            InputStream sis = socket.getInputStream();
            DataInputStream in = new DataInputStream(sis);

            String inputLine;
            String url;

            inputLine = in.readLine();

            System.out.println("input = " + inputLine);
            if (inputLine == null) {
                out.close();
                in.close();
                socket.close();
                
                return;
            }

            StringTokenizer tok = new StringTokenizer(inputLine);
            if (tok.countTokens() != 3) {
                System.out.println("this is a problem");
                out.close();
                in.close();
                socket.close();
                
                return;
            }

            String cmd = tok.nextToken();
            if ("POST".equals(cmd)) {
                post = true;
            }
            url = tok.nextToken();

            /* if (url.equals("<url to replace>")) {
                url = "<send to me>";
            } */

            HttpURLConnection conn;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
            } catch (java.net.MalformedURLException e) {
                out.close();
                in.close();
                socket.close();
                
                return;
            }

            int count = 0;
            while ((inputLine = in.readLine()) != null) {
                if (inputLine.length() != 0) {
                    //System.out.println(count + " = " + inputLine);
                    String[] tokens = inputLine.split(":");
                    String headerName = tokens[0];
                    String headerAttr = "";

                    for (int i = 1; i < tokens.length; i++) {
                        if (i != 1) {
                            headerAttr += ":";
                        }
                        headerAttr += tokens[i];
                    }

                    if (headerAttr.length() != 0 && headerAttr.charAt(0) == ' ') {
                        headerAttr = headerAttr.substring(1);
                    }
                    // System.out.println(headerName + " = " + headerAttr);
                    if (!headerName.equals("Proxy-Connection") && !headerName.startsWith("If-")) {
                        conn.addRequestProperty(headerName, headerAttr);
                    }
                    if (post && headerName.equals("Content-Length")) {
                        length = Integer.parseInt(headerAttr);
                    }
                } else {
                    break;
                }
                count++;
            }
            
            if (post) {
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setFixedLengthStreamingMode(length);

                DataOutputStream put_out = new DataOutputStream(conn.getOutputStream());
                
                byte[] post_data = new byte[length];
                in.readFully(post_data);
                put_out.write(post_data);
                put_out.flush();
                put_out.close();
            }
            
            InputStream is;

            boolean gzip = false;

            if ("gzip".equals(conn.getContentEncoding())) {
                gzip = true;
            }

            try {
                is = conn.getInputStream();
                if (gzip)
                    is = new GZIPInputStream(is);
                
                //bin = new BufferedReader(new InputStreamReader(is));
            } catch (java.io.IOException e) {
                is = conn.getErrorStream();
                if (gzip)
                    is = new GZIPInputStream(is);
                
                //bin = new BufferedReader(new InputStreamReader(is));
            }

            // handle response headers
            boolean finished = false;
            for (int i = 0; !finished; i++) {
                String headerName = conn.getHeaderFieldKey(i);
                String headerValue = conn.getHeaderField(i);
                String output;
                //System.out.println(headerName + " = " + headerValue);

                if (headerName == null && headerValue == null) {
                    output = "\n";
                    finished = true;
                } else if (headerName == null) {
                    output = headerValue + "\n";
                } else {
                    if (gzip && headerName.equals("Content-Length"))
                        continue;
                    
                    if (!headerName.equals("Keep-Alive") // headers to skip
                            && !headerName.equals("Connection")
                            && !headerName.startsWith("Proxy-")
                            && !headerName.equals("Transfer-Encoding") // httpurlconnection decodes I think
                            && !headerName.equals("Content-Encoding")
                            ) {
                        output = headerName + ": " + headerValue + "\n";
                    } else {
                        continue;
                    }
                }

                out.write(output.getBytes());
            }

            byte input[] = new byte[BUFFER_SIZE];
            int size = is.read(input, 0, BUFFER_SIZE);
            while (size != -1) {
              out.write(input, 0, size);
              size = is.read(input, 0, BUFFER_SIZE);
            }
            out.flush();

            conn.disconnect();

            // close out all resources
            if (is != null) {
                is.close();
            }
            if (out != null) {
                out.close();
            }
            if (in != null) {
                in.close();
            }
            if (socket != null) {
                socket.close();
            }
        } catch (java.net.SocketException e) {
            //ignore, client probably closed socket
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}