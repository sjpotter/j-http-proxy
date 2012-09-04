/*
 Copyright (c) 2012 Shaya Potter <spotter@gmail.com>
 
 Permission is hereby granted, free of charge, to any person obtaining a copy 
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:
 
 The above copyright notice and this permission notice shall be included in 
 all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR 
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 IN THE SOFTWARE.
*/

package org.yucs.proxy;

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
    
    //client side
    DataOutputStream out;
    DataInputStream in;

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

    private void close() throws IOException {
        if (out != null) {
            out.close();
        }
        if (in != null) {
            in.close();
        }
        if (socket != null) {
            socket.close();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void run() {
        HttpURLConnection conn;
        InputStream is;
        
        boolean post = false;
        boolean gzip = false;
        
        int length = -1;
        try {
            String input;
            
            out = new DataOutputStream(socket.getOutputStream());
            in = new DataInputStream(socket.getInputStream());

            //first line of input is the request
            input = in.readLine();
            System.out.println("input = " + input);
            if (input == null) {
                close();

                return;
            }

            StringTokenizer tok = new StringTokenizer(input);
            if (tok.countTokens() != 3) {
                System.out.println("this is a problem");
                close();

                return;
            }

            String cmd = tok.nextToken();
            if ("POST".equals(cmd)) {
                post = true;
            }
            
            String url = tok.nextToken();
            /* if (url.equals("<url to replace>")) {
                url = "<send to me>";
            } */

            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
            } catch (java.net.MalformedURLException e) {
                close();

                return;
            }

            //next N lines of input are the request properties
            //they end with a blank line
            while ((input = in.readLine()) != null) {
                if (input.length() != 0) {
                    //System.out.println(inputLine);
                    String[] tokens = input.split(":");
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

                    if (!headerName.equals("Proxy-Connection") && !headerName.startsWith("If-")) {
                        conn.addRequestProperty(headerName, headerAttr);
                    }
                    
                    if (post && headerName.equals("Content-Length")) {
                        length = Integer.parseInt(headerAttr);
                    }
                } else {
                    break;
                }
            }

            //the rest of the input is post data, if it exists
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

            //now for handling the response
            //need to handle gzip encoding
            if ("gzip".equals(conn.getContentEncoding())) {
                gzip = true;
            }

            try {
                is = conn.getInputStream();
                if (gzip)
                    is = new GZIPInputStream(is);

            } catch (java.io.IOException e) {
                is = conn.getErrorStream();
                if (gzip)
                    is = new GZIPInputStream(is);
            }

            // read the response headers and filter out ones that don't make sense to client
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

            //write out web page to client
            if (is != null) { //sometime can only have headers and no data?
                byte webpage[] = new byte[BUFFER_SIZE];
                int size = is.read(webpage, 0, BUFFER_SIZE);
                while (size != -1) {
                    out.write(webpage, 0, size);
                    size = is.read(webpage, 0, BUFFER_SIZE);
                }
                is.close();
            }
            out.flush();

            conn.disconnect();
        } catch (java.net.SocketException e) {
            //ignore, client probably closed socket
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}