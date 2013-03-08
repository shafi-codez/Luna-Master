/**
 * 
 */

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import org.apache.commons.codec.binary.Base64;
import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.PropertiesCredentials;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.ChangeMessageVisibilityRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.amazonaws.services.sqs.model.GetQueueUrlRequest;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.Placement;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.DescribeImagesResult;
import com.amazonaws.services.ec2.model.DescribeImagesRequest;
import com.amazonaws.services.ec2.model.Image;

/**
 * 
 * Main class of LunaCore
 * 
 * @author Eduardo Hernandez Marquina
 * @author Hector Veiga
 * @author Gerardo Travesedo
 * testing
 * 
 */
public class readQueue {
	private static AmazonSQS sqs;
	private static AmazonEC2 ec2;
	// Port to listen socket connections
	private static int port = 6060;

	/**
	 * Static initializer block for setting up the AWS credentials which should
	 * be in the top folder.
	 * 
	 */
	static {
		AWSCredentials credentials;
		try {
			credentials = new PropertiesCredentials(
					readQueue.class
							.getResourceAsStream("AwsCredentials.properties"));
			setEc2(new AmazonEC2Client(credentials));
			setSqs(new AmazonSQSClient(credentials));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Makes an Script in order to pass the rowID and the receiptHandle of the
	 * SQS message to the worker and executes the WorkerCode.
	 * 
	 * @param body
	 *            the rowID number in String format
	 * @param receiptHandle
	 *            the SQS rereceiptHandle of the SQS message which has the above
	 *            row ID inside
	 * @return A string with the encoded Script code which has to be executed
	 *         when the new Worker instance is launched
	 */
	private static String getUserDataScript(String rowID, String receiptHandle) {
		ArrayList<String> lines = new ArrayList<String>();
		lines.add("#! /bin/bash");
		lines.add("cd home/ubuntu/java/WorkerCode");
		// passing the RowID trough a file
		lines.add("sudo touch data.txt");
		lines.add("sudo chmod 777 data.txt");
		lines.add("echo \"" + rowID + "," + receiptHandle + "\">data.txt");
		// downloading the WorkerCode and the AWS credentials
		lines.add("sudo git clone git://github.com/eduhrr/Worker.git");
		lines.add("sudo mv Worker/Worker/src src");
		// lines.add("sudo wget https://s3.amazonaws.com/test-eduhdez/AwsCredentials.properties");
		// running WorkerCode
		// redirecting outputs and loggings to /var/www/java.txt
		lines.add("sudo touch /var/www/java.txt");
		lines.add("sudo chmod /var/www/java.txt");
		lines.add("sudo ant>/var/www/java.txt");
		// Encoding
		String str = new String(Base64.encodeBase64(join(lines, "\n")
				.getBytes()));
		return str;
	}

	public static void main(String[] args) throws Exception {
		// Taking the queue
		GetQueueUrlRequest qrequest = new GetQueueUrlRequest("iitLuna");
		String url = getSqs().getQueueUrl(qrequest).getQueueUrl();

		new MasterSocket(getPort(), url).start();
		System.out
				.println("LunaCore: Establishing Communication Socket Launched");

		String rowID = "";
		String receiptHandle = "";
		// Infinite loop: looking for SQS messages
		while (true) {
			try {

				// Taking messages
				ReceiveMessageRequest rMessage = new ReceiveMessageRequest(url);
				ReceiveMessageResult sqsResponse = new ReceiveMessageResult();
				rMessage.setMaxNumberOfMessages(1);
				while (true) {
					System.out
							.println("LunaCore: Iterating: looking for SQS messages");
					sqsResponse = getSqs().receiveMessage(rMessage);
					if (!sqsResponse.getMessages().isEmpty())
						break;
					Thread.sleep(30000); // 30 secs TODO: put a realistic value
				}

				// Showing the message
				List<Message> messages = sqsResponse.getMessages();
				String body = messages.get(0).getBody();
				String[] parts = body.split(",");
				rowID = parts[0];
				String typeOfInstance = whatTypeOfInstance(parts);
				receiptHandle = messages.get(0).getReceiptHandle();
				System.out.println("LunaCore: Found message rowID= " + rowID);
				System.out.println("LunaCore: The " + rowID + " associated instance type is "
						+ typeOfInstance);
				// System.out.println("The ReceiptHandle " + receiptHandle);

				// Taking the AMI ID dynamically
				DescribeImagesRequest imageRequest = new DescribeImagesRequest();
				List<String> mine = new ArrayList<>();
				mine.add("self");
				imageRequest.setOwners(mine);
				DescribeImagesResult imageResult = new DescribeImagesResult();
				imageResult = getEc2().describeImages(imageRequest);
				List<Image> images = imageResult.getImages();
				String instanceID = "";
				for (int image = 0; image < images.size(); image++) {
					// TODO: attention: get the description field to chose the
					// wanted AMI
					if (images.get(image).getDescription().contains("test11")) {
						instanceID = images.get(image).getImageId();
						break;
					}
				}

				// Launching a worker
				RunInstancesRequest request = new RunInstancesRequest(
						instanceID, 1, 1);
				request.setInstanceType(typeOfInstance);
				request.setKeyName("AMI");
				List<String> securityGroupIds = new ArrayList<>();
				securityGroupIds.add("LunaEdu");
				request.setSecurityGroupIds(securityGroupIds);
				request.setUserData(getUserDataScript(rowID, receiptHandle));
				// request.setAdditionalInfo("Name:Luna Worker Working!");
				Placement p = new Placement();
				p.setAvailabilityZone("us-east-1a");
				request.setPlacement(p);
				RunInstancesResult result = getEc2().runInstances(request);

				// Creating a tag name for identification
				List<Instance> instances = result.getReservation()
						.getInstances();
				CreateTagsRequest tags = new CreateTagsRequest();
				long time = System.currentTimeMillis();
				// Tag tag = new Tag("name", "luna-worker-"+time);
				List<Tag> tag = new ArrayList<Tag>();
				Tag t = new Tag();
				t.setKey("Name");
				t.setValue("luna-worker-" + time);
				tag.add(t);
				tags.setTags(tag);
				tags.withResources(instances.get(0).getInstanceId());
				// tags.withResources(instances.get(0).getInstanceId()).withTags(tag);
				getEc2().createTags(tags);

			} catch (AmazonServiceException ase) {
				System.out
						.println("LunaCore: Caught an AmazonServiceException, which means your request made it "
								+ "to Amazon SQS, but was rejected with an error response for some reason.");
				System.out.println("LunaCore: Error Message:    "
						+ ase.getMessage());
				System.out.println("LunaCore: HTTP Status Code: "
						+ ase.getStatusCode());
				System.out.println("LunaCore: AWS Error Code:   "
						+ ase.getErrorCode());
				System.out.println("LunaCore: Error Type:       "
						+ ase.getErrorType());
				System.out.println("LunaCore: Request ID:       "
						+ ase.getRequestId());
				if (!rowID.equals("") || !(rowID == null)
						&& !receiptHandle.equals("")
						|| !(receiptHandle == null)) {
					System.out.println("LunaCore: Worker " + rowID
							+ "= The worker has got an error");
					System.out.println("LunaCore: The rendering job #" + rowID
							+ " will need to be restarted");
					// bad result --> terminate visibility timeout
					ChangeMessageVisibilityRequest changeVisibility = new ChangeMessageVisibilityRequest(
							url, receiptHandle, 0);
					readQueue.getSqs()
							.changeMessageVisibility(changeVisibility);
				}
			} catch (AmazonClientException ace) {
				System.out
						.println("Caught an AmazonClientException, which means the client encountered "
								+ "a serious internal problem while trying to communicate with SQS, such as not "
								+ "being able to access the network.");
				System.out.println("Error Message: " + ace.getMessage());
				if (!rowID.equals("") || !(rowID == null)
						&& !receiptHandle.equals("")
						|| !(receiptHandle == null)) {
					System.out.println("LunaCore: Worker " + rowID
							+ "= The worker has got an error");
					System.out.println("LunaCore: The rendering job #" + rowID
							+ " will need to be restarted");
					// bad result --> terminate visibility timeout
					ChangeMessageVisibilityRequest changeVisibility = new ChangeMessageVisibilityRequest(
							url, receiptHandle, 0);
					readQueue.getSqs()
							.changeMessageVisibility(changeVisibility);
				}
			}

			// break; //uncommend for testing just 1 message
		}

	}

	/**
	 * 
	 * @param s
	 *            the String[] of the content of the SQS message
	 * @return the String of the type of instance in right format to set
	 *         directly the parameter to launch the instance
	 */
	private static String whatTypeOfInstance(String[] s) {
		if (s.length == 2 && s[1].equals("ts")) {
			return "c1.medium";
		} else { // s.length == 1
			return "t1.micro";
		}
	}

	/**
	 * Only Used for making an Single String with the data for our Script, which
	 * have to be executed when the new Worker instance is launched, ready to
	 * encode it in base64 before sending it to the instance
	 * 
	 * @param s
	 *            The String Collection of the lines of the Script
	 * @param delimiter
	 *            the delimiter we want in String format
	 * @return A single string with elements of the String Collection joined by
	 *         delimiters
	 */
	private static String join(Collection<String> s, String delimiter) {
		StringBuilder builder = new StringBuilder();
		Iterator<String> iter = s.iterator();
		while (iter.hasNext()) {
			builder.append(iter.next());
			if (!iter.hasNext()) {
				break;
			}
			builder.append(delimiter);
		}
		return builder.toString();
	}

	public static AmazonSQS getSqs() {
		return sqs;
	}

	public static void setSqs(AmazonSQS sqs) {
		readQueue.sqs = sqs;
	}

	public static AmazonEC2 getEc2() {
		return ec2;
	}

	public static void setEc2(AmazonEC2 ec2) {
		readQueue.ec2 = ec2;
	}

	public static int getPort() {
		return port;
	}
}
