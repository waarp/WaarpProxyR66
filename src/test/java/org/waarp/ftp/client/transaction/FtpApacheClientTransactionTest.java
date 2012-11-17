package org.waarp.ftp.client.transaction;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.waarp.ftp.client.WaarpFtpClient;
import org.waarp.utils.NullOutputStream;

/**
 * FTP Apache Commons-Net client transaction test
 * 
 * @author frederic
 * 
 */
public class FtpApacheClientTransactionTest extends WaarpFtpClient {
	/**
	 * 
	 * @param server
	 * @param port
	 * @param username
	 * @param passwd
	 * @param account
	 */
	public FtpApacheClientTransactionTest(String server, int port, String username,
			String passwd, String account, int isSsl) {
		super(server, port, username, passwd, account, false, isSsl, 0, 10000);
	}

	/**
	 * Ask to transfer a file
	 * 
	 * @param local
	 * @param remote
	 * @param store
	 * @return True if the file is correctly transfered
	 */
	public boolean transferFile(String local, String remote, boolean store) {
		boolean status = false;
		OutputStream output = null;
		FileInputStream fileInputStream = null;
		try {
			if (store) {
				fileInputStream = new FileInputStream(local);
				status = this.ftpClient.storeFile(remote, fileInputStream);
				fileInputStream.close();
				fileInputStream = null;
				if (!status) {
					System.err.println("Cannot finalize store like operation");
					return false;
				}
				if (!this.ftpClient.completePendingCommand()) {
					System.err.println("Cannot finalize store like operation");
					return false;
				}
				String[] results = this.executeSiteCommand("XCRC " + remote);
				for (String string : results) {
					System.err.println("XCRC: " + string);
				}
				results = this.executeSiteCommand("XMD5 " + remote);
				for (String string : results) {
					System.err.println("XMD5: " + string);
				}
				results = this.executeSiteCommand("XSHA1 " + remote);
				for (String string : results) {
					System.err.println("XSHA1: " + string);
				}
				return true;
			} else {
				output = new NullOutputStream();
				status = this.ftpClient.retrieveFile(remote,
						output);
				output.flush();
				output.close();
				if (!this.ftpClient.completePendingCommand()) {
					System.err.println("Cannot finalize store like operation");
					return false;
				}
				return status;
			}
		} catch (IOException e) {
			if (output != null)
				try {
					output.close();
				} catch (IOException e1) {
				}
			if (fileInputStream != null)
				try {
					fileInputStream.close();
				} catch (IOException e1) {
				}
			e.printStackTrace();
			return false;
		}
	}
}
