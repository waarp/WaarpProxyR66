package org.waarp.ftp.client.transaction;

import org.waarp.ftp.client.WaarpFtp4jClient;

/**
 * FTP Client using FTP4J with the test scenario
 * 
 * @author frederic
 * 
 */
public class Ftp4JClientTransactionTest extends WaarpFtp4jClient {
	/**
	 * 
	 * @param server
	 * @param port
	 * @param username
	 * @param passwd
	 * @param account
	 */
	public Ftp4JClientTransactionTest(String server, int port, String username,
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
		boolean status = super.transferFile(local, remote, store ? 1 : 0);
		/*
		 * if (status) { String [] results = this.executeSiteCommand("XCRC "+remote); for (String
		 * string : results) { System.err.println("XCRC: "+string); } results =
		 * this.executeSiteCommand("XMD5 "+remote); for (String string : results) {
		 * System.err.println("XMD5: "+string); } results =
		 * this.executeSiteCommand("XSHA1 "+remote); for (String string : results) {
		 * System.err.println("XSHA1: "+string); } }
		 */
		return status;
	}
}
