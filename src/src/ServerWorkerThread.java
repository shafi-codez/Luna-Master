import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import com.amazonaws.services.sqs.model.ChangeMessageVisibilityRequest;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;

/**
 * 
 * Class responsible for 1 communications with the worker which is dealing with
 * the rendering of the video identified by rowID
 * 
 * @author Eduardo Hernandez Marquina
 * 
 */
public class ServerWorkerThread implements Runnable {
	private Socket serverSocket;
	private String url;
	private String receiptHandle;
	private int rowID;

	public ServerWorkerThread(Socket socket, String url) throws SocketException {
		setServerSocket(socket);
		// Giving 15 mins max to receive the rowID and then other 15 to receive
		// the receiptHandle before the this thread will finish.
		getServerSocket().setSoTimeout(15 * 60 * 1000);
		this.url = url;

	}

	@Override
	public void run() {
		DataInputStream input;
		DataOutputStream output;

		try {
			input = new DataInputStream(serverSocket.getInputStream());
			output = new DataOutputStream(serverSocket.getOutputStream());

			// listen the rowID
			setRowID(Integer.valueOf(input.readUTF()));
			System.out.println("ServerWokerThread " + rowID
					+ ": Got the RowID sussccesfully");

			// listen the receiptHandle
			setReceipHandle(input.readUTF());
			System.out.println("ServerWokerThread " + rowID + ": receipHandle="
					+ getReceipHandle());

			// Giving 1 hour to the worker to send the status before this thread
			// will be closed
			getServerSocket().setSoTimeout(1 * 60 * 60 * 1000);

			// changing visibility to 11 hours
			ChangeMessageVisibilityRequest changeVisibility = new ChangeMessageVisibilityRequest(
					url, this.receiptHandle, 11 * 60 * 60);
			readQueue.getSqs().changeMessageVisibility(changeVisibility);

			// listen the worker status
			String result = "";
			do {
				result = input.readUTF();
				System.out.println("Worker " + getRowID() + "= " + result);
				if (result.equals("ERROR")) {
					// error --> terminate visibility timeout
					System.out.println("ServerWokerThread " + rowID
							+ ": worker " + getRowID() + " has got an error");
					System.out.println("ServerWokerThread " + rowID
							+ ":The rendering job #" + getRowID()
							+ " will need to be restarted");
					throw new Exception();
				}
			} while (!result.equals("Autokilling!"));

			System.out.println("ServerWokerThread " + rowID
					+ ": Deleting SQS message with rowID=" + getRowID());
			DeleteMessageRequest delRequest = new DeleteMessageRequest(url,
					this.receiptHandle);
			readQueue.getSqs().deleteMessage(delRequest);

			output.close();
			input.close();
			serverSocket.close();
			System.out.println("ServerWokerThread " + rowID
					+ ":communication thread with Worker " + getRowID()
					+ " is ending");
		} catch (SocketTimeoutException e) {
			System.out.println("ServerWokerThread " + rowID
							+ ":Communication Timeout with the worker #"
					+ getRowID() + " has elapsed");
			System.out.println("ServerWokerThread " + rowID
					+ ":The rendering job #" + getRowID()
					+ " will need to be restarted");
			ChangeMessageVisibilityRequest changeVisibility = new ChangeMessageVisibilityRequest(
					url, this.receiptHandle, 0);
			readQueue.getSqs().changeMessageVisibility(changeVisibility);
			//e.printStackTrace();
			
		} catch (Exception e) {
			// bad result --> terminate visibility timeout
			System.out.println("ServerWokerThread " + rowID
					+ ":The SQS Message #" + getRowID()
					+ " has terminated its visibility TimeOut");
			ChangeMessageVisibilityRequest changeVisibility = new ChangeMessageVisibilityRequest(
					url, this.receiptHandle, 0);
			readQueue.getSqs().changeMessageVisibility(changeVisibility);
			//e.printStackTrace();
		}
	}

	public Socket getServerSocket() {
		return serverSocket;
	}

	public void setServerSocket(Socket serverSocket) {
		this.serverSocket = serverSocket;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getReceipHandle() {
		return receiptHandle;
	}

	public void setReceipHandle(String receipHandle) {
		this.receiptHandle = receipHandle;
	}

	public int getRowID() {
		return rowID;
	}

	public void setRowID(int rowID) {
		this.rowID = rowID;
	}

}
