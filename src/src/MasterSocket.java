import java.net.*;
import java.io.*;

/**
 * Class responsible for establishing sockets communications and launching 1
 * serverWorkerThread for every communication
 * 
 * @author Eduardo Hernandez Marquina
 * 
 */
public class MasterSocket extends Thread {
	private ServerSocket serverSocket;
	private String url;

	public MasterSocket(int port, String url) throws IOException {
		this.serverSocket = new ServerSocket(port);
		this.url = url;

	}

	public void run() {
		try {
			// Infinite loop: Establishing communication sockets
			while (true) {
				Socket workerSocket = serverSocket.accept();
				Runnable newWorker = new ServerWorkerThread(workerSocket, url);
				Thread t = new Thread(newWorker);
				t.start();
			}
		} catch (IOException e) {
			System.out
					.println("MasterSocket: Something wrong establishing communications");
			e.printStackTrace();
		}
	}
}